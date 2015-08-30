package in.kevinj.natladder.common.netimpl;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.SessionType;
import in.kevinj.natladder.common.util.PacketBuilder;
import in.kevinj.natladder.common.util.PacketParser;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class ClientSession<T extends LocalRouter<T>> {
	protected static final Logger LOG = Logger.getLogger(ClientSession.class.getName());

	// Protect against potential malicious attacks. 64KB maximum packet size.
	// Enforce in receive and write to ensure legitimate packets aren't dropped.
	// Raw packets larger than this will be forwarded in chunks with a size of
	// BufferCache.DEFAULT_BUFFER_SIZE anyway, so this only really limits the
	// maximum length of control packets.
	protected static final int MAX_PACKET_LENGTH = 64 * 1024;
	private static final int HEADER_LENGTH = Integer.SIZE / 8 + Short.SIZE / 8;
	private static final int IDLE_TIME = 60000; //in milliseconds
	private static final int TIMEOUT = 15000; //in milliseconds

	public enum MessageType { HEADER, BODY, RAW }

	static {
		assert MAX_PACKET_LENGTH > BufferCache.DEFAULT_BUFFER_SIZE;
	}

	private class KeepAliveTask implements Runnable {
		private final AtomicReference<ScheduledFuture<?>> future;

		public KeepAliveTask() {
			future = new AtomicReference<ScheduledFuture<?>>(null);
		}

		public void sendPing() {
			send(new byte[] { PacketHeaders.PING }, LocalRouter.CONTROL_CODE);
		}

		public void waitForPong() {
			future.set(model.getLocalNode().getWheelTimer().schedule(idleTask, TIMEOUT, TimeUnit.MILLISECONDS));
		}

		@Override
		public void run() {
			close("Timed out after " + TIMEOUT + " milliseconds");
		}

		public void receivedPong() {
			stop();
		}

		public void stop() {
			ScheduledFuture<?> old = future.getAndSet(null);
			if (old != null)
				old.cancel(false);
		}
	}

	private final RemoteNode<T> model;
	private final Runnable postClose;
	private Runnable preClose;

	protected final AtomicBoolean closeEventsTriggered;
	private ByteBuffer readBuffer;
	private int readBufferOverflow;
	private MessageType nextMessageType;

	private int expectedRelayChainLength;

	private final KeepAliveTask heartbeatTask;
	private final Runnable idleTask;
	private ScheduledFuture<?> idleTaskFuture;

	public ClientSession(RemoteNode<T> model, Runnable onClose) {
		this.model = model;
		this.postClose = onClose;

		closeEventsTriggered = new AtomicBoolean(false);
		readBuffer = model.getLocalNode().getBufferCache().takeBuffer();
		if (model.forwardRaw()) {
			// must reserve space for packet prefix (payload length and relay chain)
			expectedRelayChainLength = model.getLocalNode().getIntermediateHops();
			readBuffer.position(Integer.SIZE / 8 + Short.SIZE / 8 * expectedRelayChainLength);
			nextMessageType = MessageType.RAW;

			heartbeatTask = null;
			idleTask = null;
		} else {
			readBuffer.limit(HEADER_LENGTH);
			nextMessageType = MessageType.HEADER;

			heartbeatTask = new KeepAliveTask();
			idleTask = new Runnable() {
				@Override
				public void run() {
					startPingTask();
				}
			};
			idleTaskFuture = model.getLocalNode().getWheelTimer().schedule(idleTask, IDLE_TIME, TimeUnit.MILLISECONDS);
		}
	}

	protected RemoteNode<T> getModel() {
		return model;
	}

	public void setPreClose(Runnable r) {
		preClose = r;
	}

	public abstract SocketAddress getAddress();

	/* package-private */ ByteBuffer readBuffer() {
		return readBuffer;
	}

	private void logDroppedPacket() {
		if (LOG.isLoggable(Level.FINER)) {
			readBuffer.flip();
			byte[] contents = new byte[readBuffer.remaining()];
			readBuffer.get(contents);

			LOG.log(Level.FINER, "Dropped packet {0}", Arrays.toString(contents));
		}
	}

	private boolean processHeader(int readBytes) {
		assert !model.forwardRaw() : "Forwarding raw in processHeader()";

		if (readBuffer.remaining() != 0)
			// keep reading until we get the full header
			return false;

		// fully read the header and parse it
		readBuffer.flip();
		assert readBuffer.remaining() == HEADER_LENGTH : readBuffer.remaining();
		int recvPktRemaining = readBuffer.getInt();
		short forwardTo = readBuffer.getShort();
		assert !readBuffer.hasRemaining() : "HEADER_LENGTH too long for actual header";
		readBuffer.clear();

		try {
			if (recvPktRemaining <= 0)
				throw new IllegalStateException("Received non-positive length packet");
			if (recvPktRemaining > MAX_PACKET_LENGTH - HEADER_LENGTH)
				throw new IllegalStateException("Received too large packet");

			model.setThisMessageDest(forwardTo);
			if (model.isThisMessageForUs()) {
				// prepare the buffer for parsing the message locally
				if (readBuffer.remaining() < recvPktRemaining) {
					// ensure we can get the entire packet in one pass
					model.getLocalNode().getBufferCache().tryReturnBuffer(readBuffer);
					readBuffer = ByteBuffer.allocate(recvPktRemaining);
				}
			} else {
				// prepare the buffer for forwarding the message
				RemoteNode<T> nextNode = model.getNextNode();
				if (nextNode != null && !nextNode.forwardRaw())
					readBuffer.putInt(recvPktRemaining - Short.SIZE / 8);
			}
		} catch (Throwable t) {
			LOG.log(Level.WARNING, "Error while processing packet header from " + model.getRemoteTypeString(), t);
			// our state is inconsistent. we don't know how to handle what we're being dealt.
			// there is no easy way out of this, so just kill ourself.
			close(t.getMessage());
		}

		nextMessageType = MessageType.BODY;
		readBufferOverflow = Math.max(0, recvPktRemaining - readBuffer.remaining());
		readBuffer.limit(recvPktRemaining - readBufferOverflow + readBuffer.position());
		return true;
	}

	private boolean processBody(int readBytes) {
		assert !model.forwardRaw() : "Forwarding raw in procesBody()";

		if (readBytes == 0)
			// non-blocking read didn't find any body content immediately after header
			return false;

		if (model.isThisMessageForUs()) {
			// received message intended for us
			if (readBuffer.remaining() != 0)
				// keep reading until we get the full body
				return false;

			// fully read the body and parse it
			try {
				readBuffer.flip();
				model.processControlPacket(new PacketParser(readBuffer) {
					@Override
					public void dispose() {
						model.getLocalNode().getBufferCache().tryReturnBuffer(buf);
					}
				});
			} catch (Throwable t) {
				LOG.log(Level.WARNING, "Error while processing control packet from " + model.getRemoteTypeString(), t);
			}

			// in case we had to allocate a bespoke non-direct buffer to handle
			// a large command packet, we will throw out readBuffer since it will
			// cause forwarding performance to suffer and its one-time length is
			// not compatible with the buffer cache.
			// in case the buffer is direct and of the standard size, we expect
			// processControlPacket() to return the buffer to cache once all
			// bytes in it have been read and processed.
			readBuffer = model.getLocalNode().getBufferCache().takeBuffer();
		} else {
			// received message to be forwarded
			int recvPktRemaining = readBufferOverflow + readBuffer.remaining();
			boolean bufferSafe = false;
			try {
				RemoteNode<T> nextNode = model.getNextNode();
				if (nextNode == null) {
					model.foundNextNodeCut();
					logDroppedPacket();
				} else {
					nextNode.getClientSession().writeMessage(readBuffer);
					bufferSafe = true;
				}
			} catch (Throwable t) {
				LOG.log(Level.WARNING, "Error while forwarding control packet from " + model.getRemoteTypeString(), t);
				logDroppedPacket();
			} finally {
				if (!bufferSafe)
					model.getLocalNode().getBufferCache().tryReturnBuffer(readBuffer);
			}

			// to minimize copying between buffers, we convert our current read buffer
			// to a write buffer and allocate ourself a new read buffer. in handing
			// control to writer, we expect writeMessage() to return the buffer to cache
			// once all bytes in the buffer have been written to nextMessageDest.
			readBuffer = model.getLocalNode().getBufferCache().takeBuffer();
			if (recvPktRemaining > 0) {
				// still have not forwarded the entire body. it's probably that we have
				// more body queued up that couldn't entirely fit into the buffer.
				readBufferOverflow = Math.max(0, recvPktRemaining - readBuffer.remaining());
				readBuffer.limit(recvPktRemaining - readBufferOverflow + readBuffer.position());
				return true;
			}
		}

		// after reading the entire body, prepare to read the next header
		readBuffer.limit(HEADER_LENGTH);
		nextMessageType = MessageType.HEADER;
		return false;
	}

	private boolean processRaw(int readBytes) {
		assert model.forwardRaw() && model.getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : (model.forwardRaw() + " " + model.getLocalNode().getLocalType());

		if (readBytes == 0)
			// non-blocking read didn't find any body content immediately after header
			return false;

		// received message to be forwarded
		short[] relayChain = model.getLocalNode().getRelayChain(model.getRemoteCode());
		if (relayChain == null)
			throw new IllegalStateException("No pipe to exit node for " + SessionType.TERMINUS + " " + model.getRemoteCode());

		int recvPktRemaining = readBuffer.remaining();
		boolean bufferSafe = false;
		try {
			RemoteNode<T> nextNode = model.getNextNode();
			if (nextNode == null) {
				model.foundNextNodeCut();
				logDroppedPacket();
			} else {
				// must first prefix packet with received packet length and the relay chain.
				// TODO: if relayChain has changed since last message was received,
				// make sure new and old relayChain are exactly the same lengths. otherwise,
				// readBuffer has to have bytes shifted over to accommodate prefix.
				if (relayChain.length != expectedRelayChainLength)
					throw new UnsupportedOperationException("Relay chain length differs from expectations. Buffer shifting not yet implemented");
	
				// calculate length of packet to forward.
				readBuffer.putInt(0, readBuffer.position() - (Integer.SIZE / 8 + Short.SIZE / 8));
				for (int i = 0; i < relayChain.length; i++)
					readBuffer.putShort(Integer.SIZE / 8 + Short.SIZE / 8 * i, relayChain[i]);
				nextNode.getClientSession().writeMessage(readBuffer);
				bufferSafe = true;
			}
		} catch (Throwable t) {
			LOG.log(Level.WARNING, "Error while forwarding raw packet from " + model.getRemoteTypeString(), t);
			logDroppedPacket();
		} finally {
			if (!bufferSafe)
				model.getLocalNode().getBufferCache().tryReturnBuffer(readBuffer);
		}

		// to minimize copying between buffers, we convert our current read buffer
		// to a write buffer and allocate ourself a new read buffer. in handing
		// control to writer, we expect writeMessage() to return the buffer to cache
		// once all bytes in the buffer have been written to nextMessageDest.
		readBuffer = model.getLocalNode().getBufferCache().takeBuffer();
		// must reserve space for packet prefix (payload length and relay chain)
		expectedRelayChainLength = relayChain.length;
		readBuffer.position(Integer.SIZE / 8 + Short.SIZE / 8 * expectedRelayChainLength);
		// note that (recvPktRemaining == readBuffer's unused capacity).
		// if readBuffer was full, it's probable that we have more body queued up
		// that couldn't entirely fit into the buffer.
		return recvPktRemaining == 0;
	}

	/* package-private */ boolean readMessage(int readBytes) {
		if (!model.forwardRaw())
			idleTaskFuture.cancel(false);
		if (readBytes == -1) {
			// connection closed
			close("EOF received");
			return false;
		}

		try {
			switch (nextMessageType) {
				case HEADER:
					return processHeader(readBytes);
				case BODY:
					return processBody(readBytes);
				case RAW:
					return processRaw(readBytes);
				default:
					throw new IllegalStateException("Invalid nextMessageType " + nextMessageType);
			}
		} finally {
			if (!model.forwardRaw())
				idleTaskFuture = model.getLocalNode().getWheelTimer().schedule(idleTask, IDLE_TIME, TimeUnit.MILLISECONDS);
		}
	}

	protected abstract void writeMessage(ByteBuffer buf);

	public void send(byte[] message, short... destinationChain) {
		ByteBuffer buf = ByteBuffer.allocate(Integer.SIZE / 8 + Short.SIZE / 8 * destinationChain.length + message.length);
		buf.putInt(message.length);
		for (short relay : destinationChain)
			buf.putShort(relay);
		buf.put(message);
		writeMessage(buf);
	}

	public PacketBuilder packetBuilder(int initialMessageLength, final short... destinationChain) {
		final int prefixLen = Integer.SIZE / 8 + Short.SIZE / 8;
		return new PacketBuilder(prefixLen + initialMessageLength) {
			@Override
			protected void initialize(ByteBuffer buf) {
				// reserve space for length
				buf.position(Integer.SIZE / 8);
				for (int i = 0; i < destinationChain.length; i++)
					buf.putShort(destinationChain[i]);
			}

			@Override
			protected void commit(ByteBuffer buf) {
				// fill in length
				buf.putInt(0, buf.position() - prefixLen);
				writeMessage(buf);
			}
		};
	}

	public PacketBuilder packetBuilder(short... destinationChain) {
		return packetBuilder(32, destinationChain);
	}

	protected abstract Channel getChannel();

	public boolean close(String reason) {
		if (closeEventsTriggered.compareAndSet(false, true)) {
			if (preClose != null)
				preClose.run();

			try {
				getChannel().close();
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Error while cutting connection with " + getAddress(), ex);
			}
			if (!model.forwardRaw())
				stopPingTask();
			//this check is thread safe - idleTaskFuture can never be null again after it has been assigned a non-null value
			if (idleTaskFuture != null)
				//client closed before we could send init packet
				idleTaskFuture.cancel(false);

			LOG.log(Level.FINE, "Cut connection with {0} ({1})", new Object[] { getAddress(), reason });
			if (postClose != null)
				postClose.run();
			return true;
		}
		return false;
	}

	public void receivedPong() {
		heartbeatTask.receivedPong();
	}

	private void startPingTask() {
		heartbeatTask.waitForPong();
		heartbeatTask.sendPing();
	}

	private void stopPingTask() {
		heartbeatTask.stop();
	}
}

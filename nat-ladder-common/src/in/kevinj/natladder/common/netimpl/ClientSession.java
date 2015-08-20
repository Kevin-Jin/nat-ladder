package in.kevinj.natladder.common.netimpl;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.util.UnorderedQueue;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientSession {
	private static final Logger LOG = Logger.getLogger(ClientSession.class.getName());

	private static final int HEADER_LENGTH = Integer.SIZE / 8 + Short.SIZE / 8;
	private static final int IDLE_TIME = 60000; //in milliseconds
	private static final int TIMEOUT = 15000; //in milliseconds

	public enum MessageType { HEADER, BODY, RAW }

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

	private final RemoteNode model;
	private final Runnable postClose;
	private final SocketChannel commChn;
	private final SelectionKey selectionKey;
	private Runnable preClose;

	private final AtomicBoolean closeEventsTriggered;
	private ByteBuffer readBuffer;
	private int readBufferOverflow;
	private MessageType nextMessageType;
	private final UnorderedQueue sendQueue;

	private KeepAliveTask heartbeatTask;
	private final Runnable idleTask = new Runnable() {
		@Override
		public void run() {
			startPingTask();
		}
	};
	private ScheduledFuture<?> idleTaskFuture;

	public ClientSession(RemoteNode model, SocketChannel channel, SelectionKey acceptedKey, Runnable onClose) {
		this.model = model;
		this.postClose = onClose;
		commChn = channel;
		selectionKey = acceptedKey;

		closeEventsTriggered = new AtomicBoolean(false);
		readBuffer = model.getLocalNode().getBufferCache().takeBuffer();
		if (model.forwardRaw()) {
			nextMessageType = MessageType.RAW;
		} else {
			readBuffer.limit(HEADER_LENGTH);
			nextMessageType = MessageType.HEADER;
		}
		sendQueue = new UnorderedQueue();

		heartbeatTask = new KeepAliveTask();
		idleTaskFuture = model.getLocalNode().getWheelTimer().schedule(idleTask, IDLE_TIME, TimeUnit.MILLISECONDS);

		LOG.log(Level.FINE, "Established connection with {0}", getAddress());
	}

	public void setPreClose(Runnable r) {
		preClose = r;
	}

	public SocketAddress getAddress() {
		return commChn.socket().getRemoteSocketAddress();
	}

	/* package-private */ ByteBuffer readBuffer() {
		return readBuffer;
	}

	private boolean processHeader(int readBytes) {
		assert !model.forwardRaw();

		if (readBuffer.remaining() != 0)
			// keep reading until we get the full header
			return false;

		// fully read the header and parse it
		readBuffer.flip();
		assert readBuffer.remaining() == HEADER_LENGTH;
		int recvPktRemaining = readBuffer.getInt();
		model.setThisMessageDest(readBuffer.getShort());
		assert !readBuffer.hasRemaining();

		readBuffer.clear();
		if (model.isThisMessageForUs()) {
			// prepare the buffer for parsing the message locally
			if (readBuffer.remaining() < recvPktRemaining) {
				// ensure we can get the entire packet in one pass
				model.getLocalNode().getBufferCache().tryReturnBuffer(readBuffer);
				readBuffer = ByteBuffer.allocate(recvPktRemaining);
			}
		} else {
			// prepare the buffer for forwarding the message
			readBuffer.putInt(recvPktRemaining);
		}

		nextMessageType = MessageType.BODY;
		readBufferOverflow = Math.max(0, recvPktRemaining - readBuffer.remaining());
		readBuffer.limit(recvPktRemaining - readBufferOverflow);
		return true;
	}

	private boolean processBody(int readBytes) {
		assert !model.forwardRaw();

		if (readBytes == 0)
			// non-blocking read didn't find any body content immediately after header
			return false;

		if (model.isThisMessageForUs()) {
			// received message intended for us
			if (readBuffer.remaining() != 0)
				// keep reading until we get the full body
				return false;

			// fully read the body and parse it
			readBuffer.flip();
			model.processControlPacket(readBuffer);

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
			ClientSession nextNode = model.getNextNode();
			if (nextNode == null) {
				model.cutLinkFound();
				model.getLocalNode().getBufferCache().tryReturnBuffer(readBuffer);
			} else {
				nextNode.writeMessage(readBuffer);
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
				readBuffer.limit(recvPktRemaining - readBufferOverflow);
				return true;
			}
		}

		// after reading the entire body, prepare to read the next header
		readBuffer.limit(HEADER_LENGTH);
		nextMessageType = MessageType.HEADER;
		return false;
	}

	private boolean processRaw(int readBytes) {
		assert model.forwardRaw();

		if (readBytes == 0)
			// non-blocking read didn't find any body content immediately after header
			return false;

		// received message to be forwarded
		short[] relayChain = model.getLocalNode().getRelayChain(model.getRemoteCode());
		int recvPktRemaining = readBuffer.remaining();
		ClientSession nextNode = model.getNextNode();
		if (nextNode == null) {
			model.cutLinkFound();
			model.getLocalNode().getBufferCache().tryReturnBuffer(readBuffer);
		} else {
			// must first prefix packet with received packet length and the relay chain.
			// TODO: if relayChain has changed since last message was received,
			// make sure new and old relayChain are exactly the same lengths. otherwise,
			// readBuffer has to have bytes shifted over to accomodate prefix.

			// calculate length of packet to forward.
			readBuffer.putInt(0, readBuffer.position() - (Integer.SIZE / 8 + Short.SIZE / 8 * relayChain.length));
			for (int i = 0; i < relayChain.length; i++)
				readBuffer.putShort(Integer.SIZE / 8 + Short.SIZE / 8 * i, relayChain[i]);
			nextNode.writeMessage(readBuffer);
		}

		// to minimize copying between buffers, we convert our current read buffer
		// to a write buffer and allocate ourself a new read buffer. in handing
		// control to writer, we expect writeMessage() to return the buffer to cache
		// once all bytes in the buffer have been written to nextMessageDest.
		readBuffer = model.getLocalNode().getBufferCache().takeBuffer();
		// must reserve space for packet prefix (payload length and relay chain)
		readBuffer.position(Integer.SIZE / 8 + Short.SIZE / 8 * relayChain.length);
		// note that (recvPktRemaining == readBuffer's unused capacity).
		// if readBuffer was full, it's probable that we have more body queued up
		// that couldn't entirely fit into the buffer.
		return recvPktRemaining == 0;
	}

	/* package-private */ boolean readMessage(int readBytes) {
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
					throw new IllegalStateException("nextMessageType is not a valid value.");
			}
		} finally {
			idleTaskFuture = model.getLocalNode().getWheelTimer().schedule(idleTask, IDLE_TIME, TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * @return 0 if not all queued messages could be sent in a non-blocking
	 * manner, 1 if all queued messages have been successfully sent, -1 if there
	 * is another flush attempt in progress, or -2 if there's an error and the
	 * channel is closed.
	 */
	/* package-private */ int tryFlushSendQueue() {
		if (!sendQueue.shouldWrite())
			return -1;
		try {
			do {
				Iterator<ByteBuffer> iter = sendQueue.pop().iterator();
				while (iter.hasNext()) {
					ByteBuffer buf = iter.next();
					if (buf.remaining() == commChn.write(buf)) {
						model.getLocalNode().getBufferCache().tryReturnBuffer(buf);
					} else {
						sendQueue.insert(buf);
						while (iter.hasNext())
							sendQueue.insert(iter.next());
						return 0;
					}
				}
			} while (!sendQueue.willBlock());
			return 1;
		} catch (IOException ex) {
			//does an IOException in write always mean an invalid channel?
			close(ex.getMessage());
			return -2;
		} finally {
			sendQueue.setCanWrite();
		}
	}

	private void writeMessage(ByteBuffer buf) {
		buf.flip();
		sendQueue.insert(buf);
		try {
			if (selectionKey.isValid() && tryFlushSendQueue() == 0) {
				selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
				selectionKey.selector().wakeup();
			}
		} catch (CancelledKeyException e) {
			//don't worry about it - session is already closed
		}
	}

	public void send(byte[] message, short... destinationChain) {
		ByteBuffer buf = ByteBuffer.allocate(Integer.SIZE / 8 + Short.SIZE / 8 * destinationChain.length + message.length);
		buf.putInt(message.length);
		for (short relay : destinationChain)
			buf.putShort(relay);
		buf.put(message);
		writeMessage(buf);
	}

	public PacketBuilder packetBuilder(int initialMessageLength, short... destinationChain) {
		return new PacketBuilder(destinationChain, initialMessageLength) {
			@Override
			protected void commit(ByteBuffer buf) {
				writeMessage(buf);
			}
		};
	}

	public PacketBuilder packetBuilder(short... destinationChain) {
		return packetBuilder(32, destinationChain);
	}

	public boolean close(String reason) {
		if (closeEventsTriggered.compareAndSet(false, true)) {
			if (preClose != null)
				preClose.run();

			try {
				commChn.close();
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Error while cutting connection with " + getAddress(), ex);
			}
			stopPingTask();
			//this check is thread safe - idleTaskFuture can never be null again after it has been assigned a non-null value
			if (idleTaskFuture != null)
				//client closed before we could send init packet
				idleTaskFuture.cancel(false);

			LOG.log(Level.FINE, "Cut connection with {0} ({1})", new Object[] { getAddress(), reason });
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

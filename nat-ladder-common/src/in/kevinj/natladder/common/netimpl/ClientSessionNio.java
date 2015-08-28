package in.kevinj.natladder.common.netimpl;

import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.util.UnorderedQueue;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.logging.Level;

public class ClientSessionNio extends ClientSession {
	private final SocketChannel commChn;
	private final SelectionKey selectionKey;
	private final UnorderedQueue sendQueue;

	public ClientSessionNio(RemoteNode model, SocketChannel channel, SelectionKey acceptedKey, Runnable onClose) {
		super(model, onClose);
		commChn = channel;
		selectionKey = acceptedKey;
		sendQueue = new UnorderedQueue();

		LOG.log(Level.FINE, "Established connection with {0}", getAddress());
	}

	@Override
	public SocketAddress getAddress() {
		return commChn.socket().getRemoteSocketAddress();
	}

	@Override
	protected void writeMessage(ByteBuffer buf) {
		if (closeEventsTriggered.get()) {
			// don't want to add any new buffers to sendQueue
			getModel().getLocalNode().getBufferCache().tryReturnBuffer(buf);
			return;
		}

		buf.flip();
		if (buf.remaining() > MAX_PACKET_LENGTH) {
			// receiving end will just deny this packet anyway
			getModel().getLocalNode().getBufferCache().tryReturnBuffer(buf);
			throw new IllegalStateException("Sending too large packet");
		}

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

	@Override
	protected Channel getChannel() {
		return commChn;
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
		Iterator<ByteBuffer> iter = null;
		ByteBuffer buf = null;
		try {
			try {
				do {
					iter = sendQueue.pop().iterator();
					while (iter.hasNext()) {
						buf = iter.next();
						if (buf.remaining() == commChn.write(buf)) {
							getModel().getLocalNode().getBufferCache().tryReturnBuffer(buf);
							buf = null;
						} else {
							return 0;
						}
					}
					iter = null;
				} while (!sendQueue.willBlock());
				return 1;
			} finally {
				// the reason why we need an inner try-finally is
				// because this must be executed before the catch clause
				// so that there aren't dangling ByteBuffers not in
				// sendQueue that we fail to return to the cache in close().
				if (buf != null)
					sendQueue.insert(buf);
				if (iter != null)
					while (iter.hasNext())
						sendQueue.insert(iter.next());
				sendQueue.setCanWrite();
			}
		} catch (IOException ex) {
			//does an IOException in write always mean an invalid channel?
			close(ex.getMessage());
			return -2;
		}
	}

	@Override
	public boolean close(String reason) {
		if (super.close(reason)) {
			// ensure all buffers in sendQueue are returned
			for (ByteBuffer buf : sendQueue.pop())
				getModel().getLocalNode().getBufferCache().tryReturnBuffer(buf);

			return true;
		}
		return false;
	}
}

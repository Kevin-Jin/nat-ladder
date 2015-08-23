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
		try {
			do {
				Iterator<ByteBuffer> iter = sendQueue.pop().iterator();
				while (iter.hasNext()) {
					ByteBuffer buf = iter.next();
					if (buf.remaining() == commChn.write(buf)) {
						getModel().getLocalNode().getBufferCache().tryReturnBuffer(buf);
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
}

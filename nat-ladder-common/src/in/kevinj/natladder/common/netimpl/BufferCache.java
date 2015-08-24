package in.kevinj.natladder.common.netimpl;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// TODO: allocate a giant direct ByteBuffer and use it as a heap.
// takeBuffer() will take a minimum size parameter and return an
// optimal unused slice of the ByteBuffer. when adjacent slices
// are returned, those slices will be coalesced into one large
// free block.
/**
 * To ensure fungibility, all ByteBuffer retrieved and stashed must be direct and have a length of DEFAULT_BUFFER_SIZE.
 *
 * @author Kevin Jin
 */
public class BufferCache {
	public static final int DEFAULT_BUFFER_SIZE = 4096;

	private final Queue<ByteBuffer> available;

	public BufferCache() {
		available = new ConcurrentLinkedQueue<ByteBuffer>();
	}

	private boolean isSatisfactory(ByteBuffer buf) {
		return buf.position() == 0 && buf.remaining() == DEFAULT_BUFFER_SIZE && !buf.isReadOnly() && buf.isDirect();
	}

	private void dispose(ByteBuffer buf) {
		// no-op
	}

	private ByteBuffer instantiate() {
		return ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE);
	}

	/* package-private */ ByteBuffer takeBuffer() {
		ByteBuffer next = available.poll();
		while (next != null && !isSatisfactory(next)) {
			dispose(next);
			next = available.poll();
		}
		if (next == null)
			next = instantiate();
		return next;
	}

	/**
	 * Any one time bespoke length buffers will be rejected by this routine.
	 * @param buf a buffer expected to be directly allocated and of size DEFAULT_BUFFER_SIZE.
	 * @return false if rejected, true is accepted.
	 */
	/* package-private */ boolean tryReturnBuffer(ByteBuffer buf) {
		buf.clear();
		if (!isSatisfactory(buf))
			return false;

		available.offer(buf);
		return true;
	}
}

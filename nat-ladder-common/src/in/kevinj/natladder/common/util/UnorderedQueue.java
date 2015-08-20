package in.kevinj.natladder.common.util;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * All methods of this class are thread safe.
 * @author GoldenKevin
 */
public class UnorderedQueue {
	private final Queue<ByteBuffer> queued;
	private final AtomicBoolean writeInProgress;

	public UnorderedQueue() {
		queued = new ConcurrentLinkedQueue<ByteBuffer>();
		writeInProgress = new AtomicBoolean(false);
	}

	/**
	 *
	 * @param orderNo a unique value received from getNextPush()
	 * @param element the ByteBuffer to queue
	 */
	public void insert(ByteBuffer element) {
		queued.offer(element);
	}

	public boolean shouldWrite() {
		return writeInProgress.compareAndSet(false, true);
	}

	public void setCanWrite() {
		writeInProgress.set(false);
	}

	public boolean willBlock() {
		return queued.isEmpty();
	}

	/**
	 *
	 * @return a list of all ByteBuffers queued as of this moment.
	 */
	public List<ByteBuffer> pop() {
		List<ByteBuffer> consecutive = new ArrayList<ByteBuffer>();
		ByteBuffer last;
		while ((last = queued.poll()) != null)
			consecutive.add(last);
		return consecutive;
	}
}
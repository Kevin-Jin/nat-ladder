package in.kevinj.natladder.entrynode;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class EntryNodeToTerminus extends RemoteNode<EntryNodeClientRegistry> {
	private final Queue<ByteBuffer> queuedRaws;
	private ScheduledFuture<?> queuedRawsExpire;

	public EntryNodeToTerminus(EntryNodeClientRegistry parentModel) {
		super(parentModel);
		queuedRaws = new LinkedList<ByteBuffer>();
	}

	@Override
	protected void setRemoteCode(short nodeCode) {
		super.setRemoteCode(nodeCode);
	}

	@Override
	public void onConnected(Map<String, Object> properties) {
		short exitNodeCode = ((Short) properties.get("exitNodeCode")).shortValue();
		getNextNode().getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8 * 2, exitNodeCode, LocalRouter.CONTROL_CODE)
			.writeByte(PacketHeaders.MAKE_PIPE)
			.writeShort(getLocalNode().getLocalCode())
			.writeShort(getRemoteCode())
		.send();
	}

	@Override
	public void deferRaw(ByteBuffer readBuffer) {
		synchronized (queuedRaws) {
			queuedRaws.add(readBuffer);
			if (queuedRawsExpire == null) {
				queuedRawsExpire = getLocalNode().getWheelTimer().schedule(new Runnable() {
					@Override
					public void run() {
						queuedRawsExpire = null;
						flushRaw();
					}
				}, 1, TimeUnit.MINUTES);
			}
		}
	}

	@Override
	public void flushRaw() {
		synchronized (queuedRaws) {
			if (queuedRawsExpire != null) {
				queuedRawsExpire.cancel(false);
				queuedRawsExpire = null;
			}

			if (!queuedRaws.isEmpty())
				getClientSession().flushQueuedRaw(queuedRaws);
		}
	}

	@Override
	public void dispose() {
		synchronized (queuedRaws) {
			if (queuedRawsExpire != null) {
				queuedRawsExpire.cancel(false);
				queuedRawsExpire = null;
			}

			if (!queuedRaws.isEmpty())
				getClientSession().disposeQueuedRaw(queuedRaws);
		}
		super.dispose();
	}
}

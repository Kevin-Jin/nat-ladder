package in.kevinj.natladder.entrynode;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;

import java.util.Map;

public class EntryNodeToTerminus extends RemoteNode<EntryNodeClientRegistry> {
	public EntryNodeToTerminus(EntryNodeClientRegistry parentModel) {
		super(parentModel);
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
}

package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.logging.Level;

public class ExitNodeToTerminus extends RemoteNode<ExitNodeClientRegistry> {
	public ExitNodeToTerminus(ExitNodeClientRegistry parentModel) {
		super(parentModel);
	}

	@Override
	protected void setRemoteCode(short nodeCode) {
		super.setRemoteCode(nodeCode);
	}

	@Override
	public void onConnected(Map<String, Object> properties) {
		short[] entryNodeRelayChain = (short[]) properties.get("entryNodeRelayChain");
		if (getLocalNode().linkEstablished(getRemoteCode(), entryNodeRelayChain)) {
			getNextNode().getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8 * 3, entryNodeRelayChain[0], LocalRouter.CONTROL_CODE)
				.writeByte(PacketHeaders.PIPE_MADE)
				.writeShort(entryNodeRelayChain[1])
				.writeShort(getLocalNode().getLocalCode())
				.writeShort(getRemoteCode())
			.send();
			LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} piped through", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress() });
		} else {
			// if other end is already disconnected, disconnect this end
			quietClose("Lost connection on source node");
		}
	}

	@Override
	public void deferRaw(ByteBuffer readBuffer) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support queueing raw messages");
	}

	@Override
	public void flushRaw() {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support queueing raw messages");
	}
}

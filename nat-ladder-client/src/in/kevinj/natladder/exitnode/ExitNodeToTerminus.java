package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;

import java.util.Collection;
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
		// unmark our connection as "in progress"
		Collection<?> ourTermini = (Collection<?>) getLocalNode().getProperty("INPROGRESS_" + entryNodeRelayChain[0]);
		boolean removed = (ourTermini != null && ourTermini.remove(Short.valueOf(entryNodeRelayChain[1])));

		Boolean ignore = (Boolean) getLocalNode().removeProperty("IGNORE_" + entryNodeRelayChain[0] + "_" + entryNodeRelayChain[1]);
		if (ignore == null || !ignore.booleanValue()) {
			// should only be false if entry node disconnected while this connection was pending.
			// in that case, ignore is true and this execution path should not have been followed.
			if (!removed)
				throw new IllegalStateException("Completed a non-existent connection (remote node code: " + entryNodeRelayChain[0] + "," + entryNodeRelayChain[1] + ")");

			// set our relay chain and relay chain reverse mapping
			getLocalNode().setProperty("RELAYCHAIN_" + getRemoteCode(), entryNodeRelayChain);
			getLocalNode().extendProperty("REVERSE_" + entryNodeRelayChain[0], getRemoteCode());
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
}

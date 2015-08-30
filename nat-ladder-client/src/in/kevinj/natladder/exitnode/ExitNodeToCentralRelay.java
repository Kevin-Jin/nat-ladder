package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.model.SessionType;
import in.kevinj.natladder.common.util.PacketParser;
import in.kevinj.natladder.common.util.Pair;

import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;

public class ExitNodeToCentralRelay extends RemoteRouter<ExitNodeClientRegistry> {
	public ExitNodeToCentralRelay(ExitNodeClientRegistry parentModel, SessionType sessionType) {
		super(parentModel);
		setSessionType(sessionType);
	}

	@Override
	public void onConnected(Map<String, Object> properties) {
		assert getSessionType() != null : getSessionType();

		// notify central relay that we are an EXIT_NODE
		getClientSession().packetBuilder(LocalRouter.CONTROL_CODE)
			.writeByte(PacketHeaders.IDENTIFY)
			// relative to central relay, exit nodes are downstream
			.writeByte(getSessionType().invert().byteValue())
			.writeString((String) properties.get("identifier"))
			.writeString((String) properties.get("password"))
			.writeInt(((Integer) properties.get("connectToPort")).intValue())
		.send();
	}

	@Override
	public RemoteNode<ExitNodeClientRegistry> getNextNode(short nodeCode) {
		// return the link to the terminus
		return getLocalNode().getDownstream(nodeCode);
	}

	@Override
	public short[] notifyFoundCutExternal(short ourTerminus) {
		short[] relayChain = super.notifyFoundCutExternal(ourTerminus);
		getLocalNode().linkLostOurEnd(ourTerminus, relayChain);

		return relayChain;
	}

	@Override
	protected void processIdentify(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processRejected(PacketParser packet) {
		byte rejectedReason = packet.readByte();
		switch (rejectedReason) {
			case PacketHeaders.REJECTED_REASON_ID_IN_USE:
				// FIXME: implement command line interaction telling user to re-enter
				// identifier and password to register with
				LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} rejected: identifier in use", new Object[] { getRemoteTypeString(), null, getClientSession().getAddress() });
				break;
			default:
				throw new IllegalStateException("Invalid rejected reason " + rejectedReason);
		}
	}

	@Override
	protected Pair<Short, short[]> processFoundCut(PacketParser packet) {
		byte foundCutType = packet.readByte();
		switch (foundCutType) {
			case PacketHeaders.FOUND_CUT_TERMINUS: {
				Pair<Short, short[]> info = super.processFoundCut(packet);

				getLocalNode().linkLostTheirEnd(info.left.shortValue(), info.right);
				return info;
			}
			case PacketHeaders.FOUND_CUT_NODE: {
				short otherNode = packet.readShort();

				getLocalNode().linksLost(this, otherNode);
				return null;
			}
			default:
				throw new IllegalStateException("Invalid found cut type " + foundCutType);
		}
	}

	@Override
	protected void processMakePipe(PacketParser packet) {
		short entryNodeCode = packet.readShort();
		short theirTerminus = packet.readShort();
		getLocalNode().linkAttempt(entryNodeCode, theirTerminus);
		getLocalNode().getClientManager().connect(getLocalNode().externalNodeFactory(),
			getLocalNode().getTerminusHost(),
			getLocalNode().getTerminusPort(),
			Collections.<String, Object>singletonMap("entryNodeRelayChain", new short[] { entryNodeCode, theirTerminus })
		);
	}

	@Override
	protected void processPipeMade(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processPipeFail(PacketParser packet) {
		short entryNodeCode = packet.readShort();
		short theirTerminus = packet.readShort();

		if (!getLocalNode().linkFailed(this, entryNodeCode, theirTerminus))
			throw new IllegalStateException("Cut a non-existent connection (remote node code: " + entryNodeCode + "," + theirTerminus + ")");
	}
}

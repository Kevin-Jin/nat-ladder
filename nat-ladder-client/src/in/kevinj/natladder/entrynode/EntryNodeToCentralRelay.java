package in.kevinj.natladder.entrynode;

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

public class EntryNodeToCentralRelay extends RemoteRouter<EntryNodeClientRegistry> {
	public EntryNodeToCentralRelay(EntryNodeClientRegistry parentModel, SessionType sessionType) {
		super(parentModel);
		setSessionType(sessionType);
	}

	@Override
	public void onConnected(Map<String, Object> properties) {
		assert getSessionType() != null : getSessionType();

		// notify central relay that we are an ENTRY_NODE
		getClientSession().packetBuilder(LocalRouter.CONTROL_CODE)
			.writeByte(PacketHeaders.IDENTIFY)
			// relative to central relay, entry nodes are upstream
			.writeByte(getSessionType().invert().byteValue())
			.writeString((String) properties.get("identifier"))
			.writeString((String) properties.get("password"))
		.send();
	}

	@Override
	protected RemoteNode<EntryNodeClientRegistry> getNextNode(short nodeCode) {
		// return the link to the terminus
		return getLocalNode().getUpstream(nodeCode);
	}

	@Override
	public short[] notifyFoundCutExternal(short ourTerminus) {
		short[] relayChain = super.notifyFoundCutExternal(ourTerminus);
		if (relayChain == null) {
			// relayChain can actually be null if connection to exit node has not yet established the pipe
			// (maybe because the other terminus is timing out on us).
			short exitNodeCode = getLocalNode().getExitNodeCode();
			getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8 * 2, exitNodeCode, LocalRouter.CONTROL_CODE)
				.writeByte(PacketHeaders.PIPE_FAIL)
				.writeShort(getLocalNode().getLocalCode())
				.writeShort(ourTerminus)
			.send();
		}

		return relayChain;
	}

	@Override
	protected void processIdentify(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processAccepted(PacketParser packet) {
		super.processAccepted(packet);

		int portNumber = packet.readInt();
		short exitNodeCode = packet.readShort();
		getLocalNode().getClientManager().listen(getLocalNode().externalNodeFactory(),
			"0.0.0.0",
			portNumber,
			Collections.<String, Object>singletonMap("exitNodeCode", Short.valueOf(exitNodeCode))
		);
		getLocalNode().setExitNodeCode(exitNodeCode);
	}

	@Override
	protected void processRejected(PacketParser packet) {
		byte rejectedReason = packet.readByte();
		switch (rejectedReason) {
			case PacketHeaders.REJECTED_REASON_ID_NOT_IN_USE:
			case PacketHeaders.REJECTED_REASON_WRONG_PASSWORD:
				// FIXME: implement command line interaction telling user to re-enter
				// identifier and password to login with
				LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} rejected: identifier or password incorrect", new Object[] { getRemoteTypeString(), null, getClientSession().getAddress() });
				break;
			default:
				throw new IllegalStateException("Invalid rejected reason " + rejectedReason);
		}
	}

	@Override
	protected Pair<Short, short[]> processFoundCut(PacketParser packet) {
		byte foundCutType = packet.readByte();
		switch (foundCutType) {
			case PacketHeaders.FOUND_CUT_TERMINUS:
				return super.processFoundCut(packet);
			case PacketHeaders.FOUND_CUT_NODE: {
				/*short otherNode = */packet.readShort();

				// lost connection to exit node. just shut ourselves down.
				getLocalNode().getClientManager().close("Lost connection to exit node", null);
				return null;
			}
			default:
				throw new IllegalStateException("Invalid found cut type " + foundCutType);
		}
	}

	@Override
	protected void processMakePipe(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processPipeMade(PacketParser packet) {
		short ourTerminus = packet.readShort();
		short exitNodeCode = packet.readShort();
		short theirTerminus = packet.readShort();

		getLocalNode().setRelayChain(ourTerminus, exitNodeCode, theirTerminus);
		RemoteNode<EntryNodeClientRegistry> terminus = getNextNode(ourTerminus);
		if (terminus != null) {
			terminus.flushRaw();
			LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} piped through", new Object[] { terminus.getRemoteTypeString(), terminus.getRemoteCode(), terminus.getClientSession().getAddress() });
		}
		// terminus can be null if notifyFoundCutExternal() sends PIPE_FAIL message to exit node
		// but exit node sends us PIPE_MADE before it receives our PIPE_FAIL
	}

	@Override
	protected void processPipeFail(PacketParser packet) {
		short ourTerminus = packet.readShort();
		RemoteNode<EntryNodeClientRegistry> terminus = getNextNode(ourTerminus);
		if (terminus != null)
			terminus.quietClose("Lost connection on source node");
		// terminus can be null if notifyFoundCutExternal() sends PIPE_FAIL message to exit node
		// but exit node sends us PIPE_MADE before it receives our PIPE_FAIL
	}
}

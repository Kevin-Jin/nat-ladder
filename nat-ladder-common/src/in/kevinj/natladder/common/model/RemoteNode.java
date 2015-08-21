package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.netimpl.ClientSession;
import in.kevinj.natladder.common.util.PacketParser;

import java.util.Map;

public class RemoteNode {
	public interface RemoteNodeFactory {
		public RemoteNode make(LocalRouter parentModel);
	}

	public static final RemoteNodeFactory externalNodeFactory = new RemoteNodeFactory() {
		@Override
		public RemoteNode make(LocalRouter parentModel) {
			RemoteNode node = new RemoteNode(parentModel);
			node.setRemoteCode(parentModel.registerNode(node));
			return node;
		}
	};

	private final LocalRouter parentModel;

	private ClientSession session;
	private short itsNodeCode;
	private boolean isNodeCodeSet;

	public RemoteNode(LocalRouter parentModel) {
		this.parentModel = parentModel;
	}

	public void setClientSession(ClientSession session) {
		this.session = session;
		session.setPreClose(new Runnable() {
			@Override
			public void run() {
				if (!getLocalNode().getClientManager().isShutdown())
					dispose();
			}
		});
	}

	protected void notifyFoundCutExternal(short ourTerminus) {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();
		assert getSessionType() != SessionType.TERMINUS : getSessionType();

		short[] relayChain = (short[]) getLocalNode().removeProperty("RELAYCHAIN_" + ourTerminus);
		if (relayChain == null)
			throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");
		getClientSession().packetBuilder(Byte.SIZE / 8 * 2 + Short.SIZE / 8, relayChain[0], LocalRouter.CONTROL_CODE)
			.writeByte(PacketHeaders.FOUND_CUT)
			.writeByte(PacketHeaders.FOUND_CUT_TERMINUS)
			.writeShort(relayChain[1])
		.send();
	}

	protected void notifyFoundCutInternal(short otherNode) {
		assert getSessionType() != SessionType.TERMINUS : getSessionType();

		getClientSession().packetBuilder(Byte.SIZE / 8 * 2 + Short.SIZE / 8, LocalRouter.CONTROL_CODE)
			.writeByte(PacketHeaders.FOUND_CUT)
			.writeByte(PacketHeaders.FOUND_CUT_NODE)
			.writeShort(otherNode)
		.send();
	}

	public ClientSession getClientSession() {
		return session;
	}

	protected void setRemoteCode(short nodeCode) {
		if (isNodeCodeSet)
			throw new IllegalStateException("Invalid remote node code " + nodeCode + " (to replace " + getRemoteCode() + ")");

		itsNodeCode = nodeCode;
		isNodeCodeSet = true;
	}

	public boolean isRemoteCodeSet() {
		return isNodeCodeSet;
	}

	public short getRemoteCode() {
		if (!isNodeCodeSet)
			throw new IllegalStateException("Invalid remote node code null");

		return itsNodeCode;
	}

	public LocalRouter getLocalNode() {
		return parentModel;
	}

	public SessionType getSessionType() {
		return SessionType.TERMINUS;
	}

	public boolean forwardRaw() {
		return true;
	}

	public void setThisMessageDest(short nodeCode) {
		throw new UnsupportedOperationException("RemoteNode does not require message dest code set");
	}

	public boolean isThisMessageForUs() {
		return false;
	}

	public void onConnected(Map<String, Object> properties) {
		assert getSessionType() == SessionType.TERMINUS : getSessionType();

		switch (getLocalNode().getLocalType()) {
			case ENTRY_NODE: {
				short exitNodeCode = ((Short) properties.get("exitNodeCode")).shortValue();
				getNextNode().getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8 * 2, exitNodeCode, LocalRouter.CONTROL_CODE)
					.writeByte(PacketHeaders.NEW_PIPE)
					.writeShort(getLocalNode().getLocalCode())
					.writeShort(getRemoteCode())
				.send();
				break;
			}
			case EXIT_NODE: {
				short[] entryNodeRelayChain = (short[]) properties.get("entryNodeRelayChain");
				// set our relay chain
				getLocalNode().setProperty("RELAYCHAIN_" + getRemoteCode(), entryNodeRelayChain);
				// relay chain reverse mapping
				getLocalNode().extendProperty("REVERSE_" + entryNodeRelayChain[0], getRemoteCode());
				getNextNode().getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8 * 3, entryNodeRelayChain[0], LocalRouter.CONTROL_CODE)
					.writeByte(PacketHeaders.PIPE_MADE)
					.writeShort(entryNodeRelayChain[1])
					.writeShort(getLocalNode().getLocalCode())
					.writeShort(getRemoteCode())
				.send();
				break;
			}
			default:
				throw new IllegalStateException("Invalid client type " + getLocalNode().getLocalType());
		}
	}

	public RemoteNode getNextNode() {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		// for terminus sessions, next node is just our connection to central relay
		switch (getLocalNode().getLocalType()) {
			case ENTRY_NODE:
				return getLocalNode().getDownstream(ClientType.CENTRAL_RELAY_NODE_CODE);
			case EXIT_NODE:
				return getLocalNode().getUpstream(ClientType.CENTRAL_RELAY_NODE_CODE);
			default:
				throw new IllegalStateException("Invalid client type " + getLocalNode().getLocalType());
		}
	}

	public void processControlPacket(PacketParser packet) {
		throw new UnsupportedOperationException("RemoteNode does not accept control packets");
	}

	public void foundNextNodeCut() {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		// if we're a TERMINUS, then next node must be central relay.
		// lost connection to the central relay. just shut ourselves down.
		getLocalNode().getClientManager().close("Lost connection to central relay", null);
	}

	protected static void disposeOnCentralRelay(LocalRouter localNode, SessionType sessionType, short nodeCode) {
		assert localNode.getLocalType() == ClientType.CENTRAL_RELAY : localNode.getLocalType();

		switch (sessionType) {
			case UPWARDS_RELAY: {
				// entry node disconnected, we have to notify just one exit node.
				EntryNodeInfo info = (EntryNodeInfo) localNode.removeProperty("ENTRY_" + nodeCode);
				RemoteNode downstream;
				if (info != null)
					if ((downstream = localNode.getDownstream(info.connectedExitNode)) != null)
						downstream.notifyFoundCutInternal(nodeCode);
					else
						throw new IllegalStateException("Cut a non-existent connection (node code: " + info.connectedExitNode + ")");
				else
					throw new IllegalStateException("nodeCode referenced non-existent entry node (node code: " + nodeCode + ")");
				// deregister UPWARDS_RELAY (i.e. entry node)
				localNode.deregisterNode(sessionType, nodeCode);
				break;
			}
			case DOWNWARDS_RELAY: {
				// exit node disconnected, we have to notify multiple entry nodes.
				ExitNodeInfo info = (ExitNodeInfo) localNode.removeProperty("EXIT_" + nodeCode);
				if (info != null) {
					localNode.removeProperty("EXITNAME_" + info.identifier);
					RemoteNode upstream;
					for (Short entryNode : info.connectedEntryNodes)
						if ((upstream = localNode.getUpstream(entryNode.shortValue())) != null)
							upstream.notifyFoundCutInternal(nodeCode);
						else
							throw new IllegalStateException("Cut a non-existent connection (node code: " + entryNode + ")");
				} else {
					throw new IllegalStateException("nodeCode referenced non-existent exit node (node code: " + nodeCode + ")");
				}
				// deregister DOWNWARDS_RELAY (i.e. exit node)
				localNode.deregisterNode(sessionType, nodeCode);
				break;
			}
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}

	protected void dispose() {
		// notify those who care that we are shutting ourself down
		switch (getLocalNode().getLocalType()) {
			case ENTRY_NODE:
			case EXIT_NODE: {
				switch (getSessionType()) {
					case TERMINUS: {
						// disconnecting from terminus. send message through central relay to notify opposite end.
						RemoteNode centralRelay = getNextNode();
						if (centralRelay != null)
							centralRelay.notifyFoundCutExternal(getRemoteCode());
						// deregister TERMINUS
						getLocalNode().deregisterNode(this);
						break;
					}
					case DOWNWARDS_RELAY:
					case UPWARDS_RELAY:
						// no need to notify CENTRAL_RELAY that we're disconnecting from them. either:
						// 1.) they'll observe our disconnection themselves and process the event
						// accordingly in their call to disposeOnCentralRelay(), or
						// 2.) they initiated the disconnection themselves, in which case our
						// notification will fall on deaf ears that just don't care.
						getLocalNode().deregisterNode(this);
						break;
				}
				break;
			}
			case CENTRAL_RELAY:
				disposeOnCentralRelay(getLocalNode(), getSessionType(), getRemoteCode());
				break;
			default:
				throw new IllegalStateException("Invalid client type " + getLocalNode().getLocalType());
		}
	}
}

package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.netimpl.ClientSession;
import in.kevinj.natladder.common.util.PacketParser;

import java.util.Collection;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RemoteNode {
	protected static final Logger LOG = Logger.getLogger(RemoteNode.class.getName());

	public interface RemoteNodeFactory {
		public RemoteNode make(LocalRouter parentModel);
		public SessionType typeToMake();
	}

	public static final RemoteNodeFactory externalNodeFactory = new RemoteNodeFactory() {
		@Override
		public RemoteNode make(LocalRouter parentModel) {
			RemoteNode node = new RemoteNode(parentModel);
			node.setRemoteCode(parentModel.registerNode(node));
			return node;
		}

		@Override
		public SessionType typeToMake() {
			return SessionType.TERMINUS;
		}
	};

	private final LocalRouter parentModel;

	private ClientSession session;
	private short itsNodeCode;
	private boolean isNodeCodeSet;
	private boolean closeQuietly;

	public RemoteNode(LocalRouter parentModel) {
		assert parentModel != null;

		this.parentModel = parentModel;
	}

	public void setClientSession(ClientSession session) {
		this.session = session;
		session.setPreClose(new Runnable() {
			@Override
			public void run() {
				if (!getLocalNode().getClientManager().isShutdown() && isRemoteCodeSet())
					dispose();
				if (getRemoteType() == ClientType.CENTRAL_RELAY)
					// lost connection to the central relay. just shut ourselves down.
					getLocalNode().getClientManager().close("Lost connection to " + ClientType.CENTRAL_RELAY, null);
			}
		});
	}

	protected void notifyFoundCutExternal(short ourTerminus) {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();
		assert getSessionType() != SessionType.TERMINUS : getSessionType();

		short[] relayChain = (short[]) getLocalNode().removeProperty("RELAYCHAIN_" + ourTerminus);
		if (relayChain != null) {
			if (getLocalNode().getLocalType() == ClientType.EXIT_NODE) { 
				Collection<?> ourTermini = (Collection<?>) getLocalNode().getProperty("REVERSE_" + relayChain[0]);
				if (ourTermini == null || !ourTermini.remove(Short.valueOf(ourTerminus)))
					throw new IllegalStateException("Inconsistent state in RELAYCHAIN_ or REVERSE_ (node code: " + ourTerminus + ")");
			}

			getClientSession().packetBuilder(Byte.SIZE / 8 * 2 + Short.SIZE / 8, relayChain[0], LocalRouter.CONTROL_CODE)
				.writeByte(PacketHeaders.FOUND_CUT)
				.writeByte(PacketHeaders.FOUND_CUT_TERMINUS)
				.writeShort(relayChain[1])
			.send();
		} else if (getLocalNode().getLocalType() == ClientType.ENTRY_NODE) {
			// relayChain can actually be null if connection to exit node has not yet established the pipe
			// (maybe because the other terminus is timing out on us).
			short exitNodeCode = ((Short) getLocalNode().getProperty("exitNodeCode")).shortValue();
			getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8 * 2, exitNodeCode, LocalRouter.CONTROL_CODE)
				.writeByte(PacketHeaders.PIPE_FAIL)
				.writeShort(getLocalNode().getLocalCode())
				.writeShort(ourTerminus)
			.send();
		} else {
			throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");
		}
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

	protected ClientType getRemoteType() {
		return null;
	}

	public static String getRemoteTypeString(SessionType sessionType, ClientType remoteType) {
		if (remoteType == null)
			return sessionType.toString();
		else
			return remoteType.toString();
	}

	public String getRemoteTypeString() {
		return getRemoteTypeString(getSessionType(), getRemoteType());
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
					.writeByte(PacketHeaders.MAKE_PIPE)
					.writeShort(getLocalNode().getLocalCode())
					.writeShort(getRemoteCode())
				.send();
				break;
			}
			case EXIT_NODE: {
				short[] entryNodeRelayChain = (short[]) properties.get("entryNodeRelayChain");
				// unmark our connection as "in progress"
				Collection<?> ourTermini = (Collection<?>) getLocalNode().getProperty("INPROGRESS_" + entryNodeRelayChain[0]);
				boolean removed = (ourTermini == null || !ourTermini.remove(Short.valueOf(entryNodeRelayChain[1])));

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
				break;
			}
			default:
				throw new IllegalStateException("Invalid client type " + getLocalNode().getLocalType());
		}
	}

	public static void onConnectFailed(LocalRouter localNode, SessionType sessionType, Map<String, Object> properties, Throwable ex) {
		switch (localNode.getLocalType()) {
			case ENTRY_NODE:
				localNode.getClientManager().close("Failed to establish connection with " + getRemoteTypeString(sessionType, RemoteRouter.getRemoteType(sessionType, localNode)), ex);
				break;
			case EXIT_NODE:
				switch (sessionType) {
					case UPWARDS_RELAY:
						localNode.getClientManager().close("Failed to establish connection with " + getRemoteTypeString(sessionType, RemoteRouter.getRemoteType(sessionType, localNode)), ex);
						break;
					case TERMINUS: {
						LOG.log(Level.WARNING, "Failed to establish connection with " + getRemoteTypeString(sessionType, RemoteRouter.getRemoteType(sessionType, localNode)), ex);
						short[] entryNodeRelayChain = (short[]) properties.get("entryNodeRelayChain");
						getNextNode(localNode).getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8, entryNodeRelayChain[0], LocalRouter.CONTROL_CODE)
							.writeByte(PacketHeaders.PIPE_FAIL)
							.writeShort(entryNodeRelayChain[1])
						.send();
						break;
					}
					default:
						throw new IllegalStateException("Invalid session type " + sessionType);
				}
				break;
			default:
				throw new IllegalStateException("Invalid client type " + localNode.getLocalType());
		}
	}

	private static RemoteNode getNextNode(LocalRouter localNode) {
		assert localNode.getLocalType() != ClientType.CENTRAL_RELAY : localNode.getLocalType();

		// for terminus sessions, next node is just our connection to central relay
		switch (localNode.getLocalType()) {
			case ENTRY_NODE:
				return localNode.getDownstream(ClientType.CENTRAL_RELAY_NODE_CODE);
			case EXIT_NODE:
				return localNode.getUpstream(ClientType.CENTRAL_RELAY_NODE_CODE);
			default:
				throw new IllegalStateException("Invalid client type " + localNode.getLocalType());
		}
	}

	public RemoteNode getNextNode() {
		return getNextNode(getLocalNode());
	}

	public void processControlPacket(PacketParser packet) {
		throw new UnsupportedOperationException("RemoteNode does not accept control packets");
	}

	public void foundNextNodeCut() {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		// if we're a TERMINUS, then next node must be central relay.
		// lost connection to the central relay. just shut ourselves down.
		getLocalNode().getClientManager().close("Lost connection to " + ClientType.CENTRAL_RELAY, null);
	}

	protected static void disposeOnCentralRelay(LocalRouter localNode, SessionType sessionType, short nodeCode, boolean quiet) {
		assert localNode.getLocalType() == ClientType.CENTRAL_RELAY : localNode.getLocalType();

		switch (sessionType) {
			case UPWARDS_RELAY: {
				// entry node disconnected, we have to notify just one exit node.
				EntryNodeInfo info = (EntryNodeInfo) localNode.removeProperty("ENTRY_" + nodeCode);
				if (info != null) {
					ExitNodeInfo exitNode = (ExitNodeInfo) localNode.getProperty("EXIT_" + info.connectedExitNode);
					RemoteNode downstream = localNode.getDownstream(info.connectedExitNode);
					if (exitNode != null && downstream != null) {
						exitNode.connectedEntryNodes.remove(Short.valueOf(nodeCode));
						if (!quiet)
							downstream.notifyFoundCutInternal(nodeCode);
					} else if (!info.isLameDuck) {
						throw new IllegalStateException("Cut a non-existent connection (node code: " + info.connectedExitNode + ")");
					}
				} else {
					throw new IllegalStateException("nodeCode referenced non-existent entry node (node code: " + nodeCode + ")");
				}
				// deregister UPWARDS_RELAY (i.e. entry node)
				localNode.deregisterNode(sessionType, nodeCode);
				break;
			}
			case DOWNWARDS_RELAY: {
				// exit node disconnected, we have to notify multiple entry nodes.
				ExitNodeInfo info = (ExitNodeInfo) localNode.removeProperty("EXIT_" + nodeCode);
				if (info != null) {
					localNode.removeProperty("EXITNAME_" + info.identifier);
					for (Short connectedEntryNode : info.connectedEntryNodes) {
						EntryNodeInfo entryNode = (EntryNodeInfo) localNode.getProperty("ENTRY_" + connectedEntryNode);
						RemoteNode upstream = localNode.getUpstream(connectedEntryNode.shortValue());
						if (entryNode != null && upstream != null) {
							entryNode.isLameDuck = true;
							if (!quiet)
								upstream.notifyFoundCutInternal(nodeCode);
						} else {
							throw new IllegalStateException("Cut a non-existent connection (node code: " + connectedEntryNode + ")");
						}
					}
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
		LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} lost", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress() });
		switch (getLocalNode().getLocalType()) {
			case ENTRY_NODE:
			case EXIT_NODE: {
				switch (getSessionType()) {
					case TERMINUS: {
						// disconnecting from terminus. send message through central relay to notify opposite end.
						RemoteNode centralRelay = getNextNode();
						if (!closeQuietly && centralRelay != null)
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
				disposeOnCentralRelay(getLocalNode(), getSessionType(), getRemoteCode(), closeQuietly);
				break;
			default:
				throw new IllegalStateException("Invalid client type " + getLocalNode().getLocalType());
		}
	}

	public void quietClose(String reason) {
		closeQuietly = true;
		getClientSession().close(reason);
	}
}

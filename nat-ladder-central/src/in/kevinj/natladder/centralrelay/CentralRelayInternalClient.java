package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.model.SessionType;
import in.kevinj.natladder.common.util.PacketParser;
import in.kevinj.natladder.common.util.Pair;

import java.util.Map;
import java.util.logging.Level;

public class CentralRelayInternalClient extends RemoteRouter<CentralRelayClientRegistry> {
	public CentralRelayInternalClient(CentralRelayClientRegistry parentModel) {
		super(parentModel);
	}

	@Override
	public void onConnected(Map<String, Object> properties) {
		assert getSessionType() == null : getSessionType();

		// no-op. wait for IDENTIFY packet
	}

	@Override
	public short[] notifyFoundCutExternal(short ourTerminus) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support sending notifications about external clients");
	}

	private void entryNodeConnected(String identifier, String password) {
		ExitNodeInfo matched = (ExitNodeInfo) getLocalNode().getProperty("EXITNAME_" + identifier);
		if (matched == null) {
			// identifier did not map to any connected exit node
			getClientSession().send(new byte[] {
				PacketHeaders.REJECTED,
				PacketHeaders.REJECTED_REASON_ID_NOT_IN_USE
			}, LocalRouter.CONTROL_CODE);
		} else if (!matched.password.equals(password)) {
			// password did not match to exit node's provided password
			getClientSession().send(new byte[] {
				PacketHeaders.REJECTED,
				PacketHeaders.REJECTED_REASON_WRONG_PASSWORD
			}, LocalRouter.CONTROL_CODE);
		} else {
			setRemoteCode(getLocalNode().registerNode(this));
			// internally keep track of which exit node is connected to each entry node
			// in case entry node disconnects and we need to notify the exit node who cares.
			getLocalNode().setProperty("ENTRY_" + getRemoteCode(), new EntryNodeInfo(identifier, matched.nodeCode));
			// internally keep track of which entry nodes are connected to each exit node
			// in case exit node disconnects and we need to notify the entry nodes who care.
			matched.connectedEntryNodes.add(Short.valueOf(getRemoteCode()));
			getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8 * 2 + Integer.SIZE / 8, LocalRouter.CONTROL_CODE)
				.writeByte(PacketHeaders.ACCEPTED)
				.writeShort(getRemoteCode())		// give entry node their unique code that central relay just generated
				.writeInt(matched.connectToPort)	// entry node will listen on the same port that exit node connects to locally
				.writeShort(matched.nodeCode)		// give entry node the exit node's unique code for their relay table
			.send();
			LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} linking with {3}", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress(), identifier });
		}
	}

	private void exitNodeConnected(String identifier, String password, int connectToPort) {
		if (getLocalNode().getProperty("EXITNAME_" + identifier) != null) {
			getClientSession().send(new byte[] {
				PacketHeaders.REJECTED,
				PacketHeaders.REJECTED_REASON_ID_IN_USE
			}, LocalRouter.CONTROL_CODE);
		} else {
			setRemoteCode(getLocalNode().registerNode(this));
			ExitNodeInfo info = new ExitNodeInfo(identifier, password, connectToPort, getRemoteCode());
			getLocalNode().setProperty("EXITNAME_" + identifier, info);
			getLocalNode().setProperty("EXIT_" + getRemoteCode(), info);
			getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8, LocalRouter.CONTROL_CODE)
				.writeByte(PacketHeaders.ACCEPTED)
				.writeShort(getRemoteCode())
			.send();
			LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} registering as {3}", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress(), identifier });
		}
	}

	@Override
	protected void processIdentify(PacketParser packet) {
		setSessionType(SessionType.valueOf(packet.readByte()));
		switch (getSessionType()) {
			case UPWARDS_RELAY: {
				String identifier = packet.readString().toLowerCase();
				String password = packet.readString();
				entryNodeConnected(identifier, password);
				break;
			}
			case DOWNWARDS_RELAY: {
				String identifier = packet.readString().toLowerCase();
				String password = packet.readString();
				int connectToPort = packet.readInt();
				exitNodeConnected(identifier, password, connectToPort);
				break;
			}
			default:
				throw new IllegalStateException("Invalid session type " + getSessionType());
		}
	}

	@Override
	protected void processAccepted(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processRejected(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected Pair<Short, short[]> processFoundCut(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processMakePipe(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processPipeMade(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processPipeFail(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	private static void disposeEntryNode(CentralRelayClientRegistry localNode, SessionType sessionType, short nodeCode, boolean quiet) {
		// entry node disconnected, we have to notify just one exit node.
		EntryNodeInfo info = (EntryNodeInfo) localNode.removeProperty("ENTRY_" + nodeCode);
		if (info != null) {
			ExitNodeInfo exitNode = (ExitNodeInfo) localNode.getProperty("EXIT_" + info.connectedExitNode);
			RemoteNode<CentralRelayClientRegistry> downstream = localNode.getDownstream(info.connectedExitNode);
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
	}

	private static void disposeExitNode(CentralRelayClientRegistry localNode, SessionType sessionType, short nodeCode, boolean quiet) {
		// exit node disconnected, we have to notify multiple entry nodes.
		ExitNodeInfo info = (ExitNodeInfo) localNode.removeProperty("EXIT_" + nodeCode);
		if (info != null) {
			localNode.removeProperty("EXITNAME_" + info.identifier);
			for (Short connectedEntryNode : info.connectedEntryNodes) {
				EntryNodeInfo entryNode = (EntryNodeInfo) localNode.getProperty("ENTRY_" + connectedEntryNode);
				RemoteNode<CentralRelayClientRegistry> upstream = localNode.getUpstream(connectedEntryNode.shortValue());
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
	}

	protected static void dispose(CentralRelayClientRegistry localNode, SessionType sessionType, short nodeCode, boolean quiet) {
		switch (sessionType) {
			case UPWARDS_RELAY:
				disposeEntryNode(localNode, sessionType, nodeCode, quiet);
				break;
			case DOWNWARDS_RELAY:
				disposeExitNode(localNode, sessionType, nodeCode, quiet);
				break;
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
		localNode.deregisterNode(sessionType, nodeCode);
	}

	@Override
	public void foundNextNodeCut() {
		// next node (NOT us) was found to be unreachable
		dispose(getLocalNode(), getSessionType().invert(), thisMessageDest, false);
	}

	@Override
	protected void dispose() {
		// notify those who care that we are shutting ourself down
		LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} lost", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress() });

		dispose(getLocalNode(), getSessionType(), getRemoteCode(), closeQuietly);
	}
}

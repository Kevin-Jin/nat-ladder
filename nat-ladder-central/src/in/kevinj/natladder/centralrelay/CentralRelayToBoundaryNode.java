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

public class CentralRelayToBoundaryNode extends RemoteRouter<CentralRelayClientRegistry> {
	public CentralRelayToBoundaryNode(CentralRelayClientRegistry parentModel) {
		super(parentModel);
	}

	@Override
	public void onConnected(Map<String, Object> properties) {
		assert getSessionType() == null : getSessionType();

		// no-op. wait for IDENTIFY packet
	}

	@Override
	protected RemoteNode<CentralRelayClientRegistry> getNextNode(short nodeCode) {
		switch (getSessionType()) {
			case UPWARDS_RELAY:
				return getLocalNode().getDownstream(nodeCode);
			case DOWNWARDS_RELAY:
				return getLocalNode().getUpstream(nodeCode);
			default:
				throw new IllegalStateException("Invalid session type " + getSessionType());
		}
	}

	@Override
	public short[] notifyFoundCutExternal(short ourTerminus) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support sending notifications about external clients");
	}

	private void entryNodeConnected(String identifier, String password) {
		ExitNodeInfo exitNode = getLocalNode().getExitNode(identifier);
		if (exitNode == null) {
			// identifier did not map to any connected exit node
			getClientSession().send(new byte[] {
				PacketHeaders.REJECTED,
				PacketHeaders.REJECTED_REASON_ID_NOT_IN_USE
			}, LocalRouter.CONTROL_CODE);
		} else if (!exitNode.password.equals(password)) {
			// password did not match to exit node's provided password
			getClientSession().send(new byte[] {
				PacketHeaders.REJECTED,
				PacketHeaders.REJECTED_REASON_WRONG_PASSWORD
			}, LocalRouter.CONTROL_CODE);
		} else {
			setRemoteCode(getLocalNode().registerEntryNode(this, identifier, exitNode));
			getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8 * 2 + Integer.SIZE / 8, LocalRouter.CONTROL_CODE)
				.writeByte(PacketHeaders.ACCEPTED)
				.writeShort(getRemoteCode())		// give entry node their unique code that central relay just generated
				.writeInt(exitNode.connectToPort)	// entry node will listen on the same port that exit node connects to locally
				.writeShort(exitNode.nodeCode)		// give entry node the exit node's unique code for their relay table
			.send();
			LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} linking with {3}", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress(), identifier });
		}
	}

	private void exitNodeConnected(String identifier, String password, int connectToPort) {
		if (getLocalNode().getExitNode(identifier) != null) {
			getClientSession().send(new byte[] {
				PacketHeaders.REJECTED,
				PacketHeaders.REJECTED_REASON_ID_IN_USE
			}, LocalRouter.CONTROL_CODE);
		} else {
			setRemoteCode(getLocalNode().registerExitNode(this, identifier, password, connectToPort));
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
		EntryNodeInfo info = localNode.deregisterEntryNode(sessionType, nodeCode);
		if (info != null) {
			ExitNodeInfo exitNode = localNode.getExitNode(info.connectedExitNode);
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
		ExitNodeInfo info = localNode.deregisterExitNode(sessionType, nodeCode);
		if (info != null) {
			for (Short connectedEntryNode : info.connectedEntryNodes) {
				EntryNodeInfo entryNode = localNode.getEntryNode(connectedEntryNode);
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

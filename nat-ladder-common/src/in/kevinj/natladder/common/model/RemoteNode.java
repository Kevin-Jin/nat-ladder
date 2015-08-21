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
				// notify source node (entry/exit) that our connection to external is closed.
				short[] relayChain = parentModel.getRelayChain(getRemoteCode());
				RemoteNode nextNode = getNextNode();
				if (nextNode != null)
					nextNode.getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8, relayChain[0], LocalRouter.CONTROL_CODE).writeByte(PacketHeaders.FOUND_CUT).writeShort(relayChain[1]).send();
			}
		});
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
		// no-op. we don't need to identify ourself to an external service
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

	public void cutLinkFound() {
		getClientSession().close("Connection severed on source node");
	}
}

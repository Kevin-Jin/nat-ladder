package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.util.PacketParser;

import java.util.Map;

public class RemoteRouter extends RemoteNode {
	public static final RemoteNodeFactory internalNodeFactory = new RemoteNodeFactory() {
		@Override
		public RemoteNode make(LocalRouter parentModel) {
			return new RemoteRouter(parentModel);
		}
	};

	public static final RemoteNodeFactory upwardsRelayFactory = new RemoteNodeFactory() {
		@Override
		public RemoteNode make(LocalRouter parentModel) {
			return new RemoteRouter(parentModel, SessionType.UPWARDS_RELAY);
		}
	};

	public static final RemoteNodeFactory downwardsRelayFactory = new RemoteNodeFactory() {
		@Override
		public RemoteNode make(LocalRouter parentModel) {
			return new RemoteRouter(parentModel, SessionType.DOWNWARDS_RELAY);
		}
	};

	private SessionType sessionType;
	private short thisMessageDest;

	public RemoteRouter(LocalRouter parentModel) {
		super(parentModel);
	}

	public RemoteRouter(LocalRouter parentModel, SessionType sessionType) {
		this(parentModel);
		this.sessionType = sessionType;
	}

	@Override
	public SessionType getSessionType() {
		return sessionType;
	}

	private void setSessionType(SessionType sessionType) {
		if (getSessionType() != null || sessionType == SessionType.TERMINUS || sessionType == null)
			throw new IllegalStateException("Invalid session type " + sessionType + " (to replace " + getSessionType() + ")");

		this.sessionType = sessionType;
	}

	@Override
	public boolean forwardRaw() {
		return false;
	}

	@Override
	public void setThisMessageDest(short nodeCode) {
		thisMessageDest = nodeCode;
	}

	@Override
	public boolean isThisMessageForUs() {
		return thisMessageDest == LocalRouter.CONTROL_CODE;
	}

	@Override
	public void onConnected(Map<String, Object> properties) {
		assert (getLocalNode().getLocalType() == ClientType.CENTRAL_RELAY) == (sessionType == null);
		if (sessionType != null)
			getClientSession().send(new byte[] { PacketHeaders.IDENTIFY, sessionType.invert().byteValue() }, LocalRouter.CONTROL_CODE);
	}

	@Override
	public RemoteNode getNextNode() {
		if (getSessionType() == null)
			throw new IllegalStateException("Received forward request before IDENTIFY");

		switch (getSessionType()) {
			case UPWARDS_RELAY:
				return getLocalNode().getDownstream(thisMessageDest);
			case DOWNWARDS_RELAY:
				return getLocalNode().getUpstream(thisMessageDest);
			default:
				throw new IllegalStateException("Invalid session type " + getSessionType());
		}
	}

	private void processIdentify(PacketParser packet) {
		assert getLocalNode().getLocalType() == ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		// received by central relay after entry/exit node connects
		setSessionType(SessionType.valueOf(packet.readByte()));
		setRemoteCode(getLocalNode().registerNode(this));
		getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8, LocalRouter.CONTROL_CODE).writeByte(PacketHeaders.ACCEPTED).writeShort(getRemoteCode()).send();
	}

	private void processAccepted(PacketParser packet) {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		// received by entry/exit node after connecting to central relay
		setRemoteCode(ClientType.CENTRAL_RELAY_NODE_CODE);
		getLocalNode().registerNode(this);
		getLocalNode().setLocalCode(packet.readShort());
	}

	private void processFoundCut(PacketParser packet) {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		getLocalNode().removeFromRelayTable(getRemoteCode(), packet.readShort());
	}

	@Override
	public void processControlPacket(PacketParser packet) {
		try {
			byte op = packet.readByte();
			switch (op) {
				case PacketHeaders.IDENTIFY:
					processIdentify(packet);
					break;
				case PacketHeaders.ACCEPTED:
					processAccepted(packet);
					break;
				case PacketHeaders.PING:
					getClientSession().send(new byte[] { PacketHeaders.PONG }, LocalRouter.CONTROL_CODE);
					break;
				case PacketHeaders.PONG:
					getClientSession().receivedPong();
					break;
				case PacketHeaders.FOUND_CUT:
					processFoundCut(packet);
					break;
				default:
					throw new IllegalStateException("Invalid operation " + op);
			}
		} finally {
			packet.dispose();
		}
	}

	@Override
	public void cutLinkFound() {
		getLocalNode().removeFromRelayTable(getRemoteCode());
	}
}

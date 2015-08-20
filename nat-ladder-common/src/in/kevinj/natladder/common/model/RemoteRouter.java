package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.netimpl.ClientSession;

import java.nio.ByteBuffer;

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
		if (this.sessionType != null)
			throw new IllegalStateException("Invalid session type");

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
	public void sendInitPacket() {
		assert (getLocalNode().getLocalType() == ClientType.CENTRAL_RELAY) == (sessionType == null);
		if (sessionType != null)
			getClientSession().send(new byte[] { PacketHeaders.IDENTIFY, sessionType.invert().byteValue() }, LocalRouter.CONTROL_CODE);
	}

	@Override
	public ClientSession getNextNode() {
		if (getSessionType() == null)
			throw new IllegalStateException("Received forward request before IDENTIFY");

		switch (getSessionType()) {
			case UPWARDS_RELAY:
				return getLocalNode().getDownstream(thisMessageDest);
			case DOWNWARDS_RELAY:
				return getLocalNode().getUpstream(thisMessageDest);
			default:
				throw new IllegalStateException("Invalid session type");
		}
	}

	@Override
	public void processControlPacket(ByteBuffer readBuffer) {
		try {
			switch (readBuffer.get()) {
				case PacketHeaders.IDENTIFY:
					// received by central relay after entry/exit node connects
					setSessionType(SessionType.valueOf(readBuffer.get()));
					setRemoteCode(getLocalNode().registerNode(this));
					getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8, LocalRouter.CONTROL_CODE).writeByte(PacketHeaders.ACCEPTED).writeShort(getRemoteCode()).send();
					break;
				case PacketHeaders.ACCEPTED:
					// received by entry/exit node after connecting to central relay
					setRemoteCode(ClientType.CENTRAL_RELAY_NODE_CODE);
					getLocalNode().registerNode(this);
					getLocalNode().setLocalCode(readBuffer.getShort());
					break;
				case PacketHeaders.PING:
					getClientSession().send(new byte[] { PacketHeaders.PONG }, LocalRouter.CONTROL_CODE);
					break;
				case PacketHeaders.PONG:
					getClientSession().receivedPong();
					break;
				case PacketHeaders.FOUND_CUT:
					assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY;
					getLocalNode().removeFromRelayTable(getRemoteCode(), readBuffer.getShort());
					break;
			}
		} finally {
			getLocalNode().getBufferCache().tryReturnBuffer(readBuffer);
		}
	}

	@Override
	public void cutLinkFound() {
		getLocalNode().removeFromRelayTable(getRemoteCode());
	}
}

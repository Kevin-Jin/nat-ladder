package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.util.PacketParser;
import in.kevinj.natladder.common.util.Pair;

import java.util.logging.Level;

public abstract class RemoteRouter<T extends LocalRouter<T>> extends RemoteNode<T> {
	private SessionType sessionType;
	protected short thisMessageDest;

	public RemoteRouter(T parentModel) {
		super(parentModel);
	}

	@Override
	public SessionType getSessionType() {
		return sessionType;
	}

	@Override
	protected ClientType getRemoteType() {
		return getLocalNode().getRemoteType(getSessionType());
	}

	protected void setSessionType(SessionType sessionType) {
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

	protected abstract RemoteNode<T> getNextNode(short nodeCode);

	@Override
	public RemoteNode<T> getNextNode() {
		return getNextNode(thisMessageDest);
	}

	@Override
	public short[] notifyFoundCutExternal(short ourTerminus) {
		short[] relayChain = (short[]) getLocalNode().removeRelayChain(ourTerminus);
		if (relayChain != null) {
			getClientSession().packetBuilder(Byte.SIZE / 8 * 2 + Short.SIZE / 8, relayChain[0], LocalRouter.CONTROL_CODE)
				.writeByte(PacketHeaders.FOUND_CUT)
				.writeByte(PacketHeaders.FOUND_CUT_TERMINUS)
				.writeShort(relayChain[1])
			.send();
		}
		return relayChain;
	}

	@Override
	public void notifyFoundCutInternal(short otherNode) {
		getClientSession().packetBuilder(Byte.SIZE / 8 * 2 + Short.SIZE / 8, LocalRouter.CONTROL_CODE)
			.writeByte(PacketHeaders.FOUND_CUT)
			.writeByte(PacketHeaders.FOUND_CUT_NODE)
			.writeShort(otherNode)
		.send();
	}

	protected abstract void processIdentify(PacketParser packet);

	protected void processAccepted(PacketParser packet) {
		setRemoteCode(ClientType.CENTRAL_RELAY_NODE_CODE);
		getLocalNode().registerNode(this);
		getLocalNode().setLocalCode(packet.readShort());
		LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} established", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress() });
	}

	protected abstract void processRejected(PacketParser packet);

	protected Pair<Short, short[]> processFoundCut(PacketParser packet) {
		short ourTerminus = packet.readShort();
		short[] relayChain = (short[]) getLocalNode().removeRelayChain(ourTerminus);
		if (relayChain == null)
			throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");

		RemoteNode<T> externalConn = getNextNode(ourTerminus);
		if (externalConn != null)
			// we're being notified by CENTRAL_RELAY. no need to echo back the cut notification to CENTRAL_RELAY
			externalConn.quietClose("Lost connection on source node");
		else
			throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");

		return new Pair<Short, short[]>(Short.valueOf(ourTerminus), relayChain);
	}

	protected abstract void processMakePipe(PacketParser packet);

	protected abstract void processPipeMade(PacketParser packet);

	protected abstract void processPipeFail(PacketParser packet);

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
				case PacketHeaders.REJECTED:
					processRejected(packet);
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
				case PacketHeaders.MAKE_PIPE:
					processMakePipe(packet);
					break;
				case PacketHeaders.PIPE_MADE:
					processPipeMade(packet);
					break;
				case PacketHeaders.PIPE_FAIL:
					processPipeFail(packet);
					break;
				default:
					throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet operation " + op);
			}
		} finally {
			packet.dispose();
		}
	}

	@Override
	public void foundNextNodeCut() {
		// terminus disconnected. send message through central relay to notify opposite end.
		notifyFoundCutExternal(thisMessageDest);
		// just in case... deregister TERMINUS
		getLocalNode().deregisterNode(SessionType.TERMINUS, thisMessageDest);
	}

	@Override
	protected void dispose() {
		// notify those who care that we are shutting ourself down
		LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} lost", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress() });

		// no need to notify CENTRAL_RELAY that we're disconnecting from them. either:
		// 1.) they'll observe our disconnection themselves and process the event
		// accordingly in their call to disposeOnCentralRelay(), or
		// 2.) they initiated the disconnection themselves, in which case our
		// notification will fall on deaf ears that just don't care.
		getLocalNode().deregisterNode(this);
	}
}

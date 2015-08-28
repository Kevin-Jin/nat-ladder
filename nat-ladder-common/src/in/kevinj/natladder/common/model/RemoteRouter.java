package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.util.PacketParser;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;

public class RemoteRouter extends RemoteNode {
	public static final RemoteNodeFactory internalNodeFactory = new RemoteNodeFactory() {
		@Override
		public RemoteNode make(LocalRouter parentModel) {
			return new RemoteRouter(parentModel);
		}

		@Override
		public SessionType typeToMake() {
			return null;
		}
	};

	public static final RemoteNodeFactory upwardsRelayFactory = new RemoteNodeFactory() {
		@Override
		public RemoteNode make(LocalRouter parentModel) {
			return new RemoteRouter(parentModel, SessionType.UPWARDS_RELAY);
		}

		@Override
		public SessionType typeToMake() {
			return SessionType.UPWARDS_RELAY;
		}
	};

	public static final RemoteNodeFactory downwardsRelayFactory = new RemoteNodeFactory() {
		@Override
		public RemoteNode make(LocalRouter parentModel) {
			return new RemoteRouter(parentModel, SessionType.DOWNWARDS_RELAY);
		}

		@Override
		public SessionType typeToMake() {
			return SessionType.DOWNWARDS_RELAY;
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

	public static ClientType getRemoteType(SessionType sessionType, LocalRouter localNode) {
		SessionType link = sessionType;
		ClientType us = localNode.getLocalType();
		switch (us) {
			case CENTRAL_RELAY:
				switch (link) {
					case DOWNWARDS_RELAY:
						return ClientType.EXIT_NODE;
					case UPWARDS_RELAY:
						return ClientType.ENTRY_NODE;
					default:
						throw new IllegalStateException("Invalid session type " + link);
				}
			case EXIT_NODE:
			case ENTRY_NODE:
				if (link == SessionType.DOWNWARDS_RELAY && us == ClientType.ENTRY_NODE
						|| link == SessionType.UPWARDS_RELAY && us == ClientType.EXIT_NODE)
					return ClientType.CENTRAL_RELAY;
				if (link == SessionType.TERMINUS && us == ClientType.EXIT_NODE
						|| link == SessionType.TERMINUS && us == ClientType.ENTRY_NODE)
					return null;
				throw new IllegalStateException("Invalid session type " + link + " and client type " + us);
			default:
				throw new IllegalStateException("Invalid client type " + us);
		}
	}

	@Override
	protected ClientType getRemoteType() {
		return getRemoteType(getSessionType(), getLocalNode());
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
		if (getSessionType() != null) {
			assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

			switch (getSessionType()) {
				case DOWNWARDS_RELAY:
					// notify central relay that we are an ENTRY_NODE
					getClientSession().packetBuilder(LocalRouter.CONTROL_CODE)
						.writeByte(PacketHeaders.IDENTIFY)
						// relative to central relay, entry nodes are upstream
						.writeByte(getSessionType().invert().byteValue())
						.writeString((String) properties.get("identifier"))
						.writeString((String) properties.get("password"))
					.send();
					break;
				case UPWARDS_RELAY:
					// notify central relay that we are an EXIT_NODE
					getClientSession().packetBuilder(LocalRouter.CONTROL_CODE)
						.writeByte(PacketHeaders.IDENTIFY)
						// relative to central relay, exit nodes are downstream
						.writeByte(getSessionType().invert().byteValue())
						.writeString((String) properties.get("identifier"))
						.writeString((String) properties.get("password"))
						.writeInt(((Integer) properties.get("connectToPort")).intValue())
					.send();
					break;
				default:
					throw new IllegalStateException("Invalid session type " + getSessionType());
			}
		} else {
			assert getLocalNode().getLocalType() == ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();
		}
	}

	private RemoteNode getNextNode(short nodeCode) {
		if (getSessionType() == null)
			throw new IllegalStateException("Received forward request before IDENTIFY");

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
	public RemoteNode getNextNode() {
		return getNextNode(thisMessageDest);
	}

	private void processIdentify(PacketParser packet) {
		assert getLocalNode().getLocalType() == ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		setSessionType(SessionType.valueOf(packet.readByte()));
		switch (getSessionType()) {
			case UPWARDS_RELAY: {
				// ENTRY_NODE connected
				String identifier = packet.readString().toLowerCase();
				String password = packet.readString();
				// find the connected exit node that maps to the given identifier
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
				break;
			}
			case DOWNWARDS_RELAY: {
				// EXIT_NODE connected
				String identifier = packet.readString().toLowerCase();
				String password = packet.readString();
				int connectToPort = packet.readInt();
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
				break;
			}
			default:
				throw new IllegalStateException("Invalid session type " + getSessionType());
		}
	}

	private void processAccepted(PacketParser packet) {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		setRemoteCode(ClientType.CENTRAL_RELAY_NODE_CODE);
		getLocalNode().registerNode(this);
		getLocalNode().setLocalCode(packet.readShort());
		switch (getLocalNode().getLocalType()) {
			case ENTRY_NODE: {
				int portNumber = packet.readInt();
				short exitNodeCode = packet.readShort();
				getLocalNode().getClientManager().listen(externalNodeFactory,
					"0.0.0.0",
					portNumber,
					Collections.<String, Object>singletonMap("exitNodeCode", Short.valueOf(exitNodeCode))
				);
				break;
			}
			case EXIT_NODE:
				// no-op
				break;
			default:
				throw new IllegalStateException("Invalid client type " + getLocalNode().getLocalType());
		}
		LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} established", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress() });
	}

	private void processRejected(PacketParser packet) {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		byte rejectedReason = packet.readByte();
		switch (rejectedReason) {
			case PacketHeaders.REJECTED_REASON_ID_IN_USE:
				assert getLocalNode().getLocalType() == ClientType.EXIT_NODE : getLocalNode().getLocalType();

				// FIXME: implement command line interaction telling user to re-enter
				// identifier and password to register with
				LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} rejected: identifier in use", new Object[] { getRemoteTypeString(), null, getClientSession().getAddress() });
				break;
			case PacketHeaders.REJECTED_REASON_ID_NOT_IN_USE:
			case PacketHeaders.REJECTED_REASON_WRONG_PASSWORD:
				assert getLocalNode().getLocalType() == ClientType.ENTRY_NODE : getLocalNode().getLocalType();

				// FIXME: implement command line interaction telling user to re-enter
				// identifier and password to login with
				LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} rejected: identifier or password incorrect", new Object[] { getRemoteTypeString(), null, getClientSession().getAddress() });
				break;
			default:
				throw new IllegalStateException("Invalid rejected reason " + rejectedReason);
		}
	}

	private void processFoundCut(PacketParser packet) {
		assert getLocalNode().getLocalType() != ClientType.CENTRAL_RELAY : getLocalNode().getLocalType();

		byte foundCutType = packet.readByte();
		switch (foundCutType) {
			case PacketHeaders.FOUND_CUT_TERMINUS: {
				short ourTerminus = packet.readShort();
				short[] relayChain = (short[]) getLocalNode().removeProperty("RELAYCHAIN_" + ourTerminus);
				if (relayChain == null)
					throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");

				switch (getSessionType()) {
					case UPWARDS_RELAY: {
						assert getLocalNode().getLocalType() == ClientType.EXIT_NODE : getLocalNode().getLocalType();

						RemoteNode externalConn = getLocalNode().getDownstream(ourTerminus);
						if (externalConn != null)
							// we're being notified by CENTRAL_RELAY. no need to echo back the cut notification to CENTRAL_RELAY
							externalConn.quietClose("Lost connection on source node");
						else
							throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");
						break;
					}
					case DOWNWARDS_RELAY: {
						assert getLocalNode().getLocalType() == ClientType.ENTRY_NODE : getLocalNode().getLocalType();

						RemoteNode externalConn = getLocalNode().getUpstream(ourTerminus);
						if (externalConn != null)
							// we're being notified by CENTRAL_RELAY. no need to echo back the cut notification to CENTRAL_RELAY
							externalConn.quietClose("Lost connection on source node");
						else
							throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");
						break;
					}
					default:
						throw new IllegalStateException("Invalid session type " + getSessionType());
				}
				break;
			}
			case PacketHeaders.FOUND_CUT_NODE: {
				short otherNode = packet.readShort();
				switch (getSessionType()) {
					case UPWARDS_RELAY:
						assert getLocalNode().getLocalType() == ClientType.EXIT_NODE : getLocalNode().getLocalType();

						// cut terminus connections that relay through the provided entry node
						Collection<?> ourTermini = (Collection<?>) getLocalNode().removeProperty("REVERSE_" + otherNode);
						if (ourTermini != null)
							// at least one pipe exists through the entry node
							for (Object ourTerminus : ourTermini)
								// we're being notified by CENTRAL_RELAY. no need to echo back the cut notification to CENTRAL_RELAY
								getLocalNode().getDownstream((Short) ourTerminus).quietClose("Lost connection to entry node");
						break;
					case DOWNWARDS_RELAY:
						assert getLocalNode().getLocalType() == ClientType.ENTRY_NODE : getLocalNode().getLocalType();

						// lost connection to exit node. just shut ourselves down.
						getLocalNode().getClientManager().close("Lost connection to exit node", null);
						break;
					default:
						throw new IllegalStateException("Invalid session type " + getSessionType());
				}
				break;
			}
			default:
				throw new IllegalStateException("Invalid found cut type " + foundCutType);
		}
	}

	private void processMakePipe(PacketParser packet) {
		assert getLocalNode().getLocalType() == ClientType.EXIT_NODE : getLocalNode().getLocalType();

		short entryNodeCode = packet.readShort();
		short terminusCode = packet.readShort();
		getLocalNode().getClientManager().connect(externalNodeFactory,
			(String) getLocalNode().getProperty("terminusHost"),
			((Integer) getLocalNode().getProperty("terminusPort")).intValue(),
			Collections.<String, Object>singletonMap("entryNodeRelayChain", new short[] { entryNodeCode, terminusCode })
		);
	}

	private void processPipeMade(PacketParser packet) {
		assert getLocalNode().getLocalType() == ClientType.ENTRY_NODE : getLocalNode().getLocalType();

		short ourTerminus = packet.readShort();
		short exitNodeCode = packet.readShort();
		short terminusCode = packet.readShort();
		// set our relay chain
		getLocalNode().setProperty("RELAYCHAIN_" + ourTerminus, new short[] { exitNodeCode, terminusCode });
		RemoteNode terminus = getNextNode(ourTerminus);
		LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} piped through", new Object[] { terminus.getRemoteTypeString(), terminus.getRemoteCode(), terminus.getClientSession().getAddress() });
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
				default:
					throw new IllegalStateException("Invalid operation " + op);
			}
		} finally {
			packet.dispose();
		}
	}

	@Override
	public void foundNextNodeCut() {
		assert getSessionType() != SessionType.TERMINUS : getSessionType();

		// next node (NOT us) was found to be unreachable
		switch (getLocalNode().getLocalType()) {
			case ENTRY_NODE:
			case EXIT_NODE:
				// terminus disconnected. send message through central relay to notify opposite end.
				notifyFoundCutExternal(thisMessageDest);
				// just in case... deregister TERMINUS
				getLocalNode().deregisterNode(SessionType.TERMINUS, thisMessageDest);
				break;
			case CENTRAL_RELAY:
				// if we're an UPWARDS_RELAY, then the disconnected next node is a DOWNWARDS_RELAY
				// if we're a DOWNWARDS_RELAY, then the disconnected next node is an UPWARDS_RELAY
				disposeOnCentralRelay(getLocalNode(), getSessionType().invert(), thisMessageDest, false);
				break;
			default:
				throw new IllegalStateException("Invalid client type " + getLocalNode().getLocalType());
		}
	}
}

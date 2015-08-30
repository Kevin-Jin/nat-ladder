package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.model.SessionType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

public class ExitNodeClientRegistry extends LocalRouter<ExitNodeClientRegistry> {
	private static class EntryNodeInfo {
		public Set<Short> ignoreOnConnect;
		public Set<Short> inProgressPipes;
		public Set<Short> establishedPipes;
		public Map<Short, Short> ourTerminus;

		public EntryNodeInfo() {
			ignoreOnConnect = new HashSet<Short>();
			inProgressPipes = new HashSet<Short>();
			establishedPipes = new HashSet<Short>();
			ourTerminus = new HashMap<Short, Short>();
		}
	}

	private final String terminusHost;
	private final int terminusPort;
	private final ReadWriteLock lock;
	private final Map<Short, EntryNodeInfo> linkedEntryNodes;
	private final Map<Short, short[]> relayChains;

	public ExitNodeClientRegistry(ClientType localType, String terminusHost, int terminusPort) {
		super(localType);
		lock = new ReentrantReadWriteLock();
		linkedEntryNodes = new HashMap<Short, EntryNodeInfo>();
		relayChains = new HashMap<Short, short[]>();

		this.terminusHost = terminusHost;
		this.terminusPort = terminusPort;
	}

	@Override
	public RemoteNodeFactory<ExitNodeClientRegistry> internalNodeFactory() {
		return new RemoteNodeFactory<ExitNodeClientRegistry>() {
			@Override
			public ExitNodeToCentralRelay make(ExitNodeClientRegistry parentModel) {
				return new ExitNodeToCentralRelay(parentModel, SessionType.UPWARDS_RELAY);
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.UPWARDS_RELAY;
			}
		};
	}

	@Override
	public RemoteNodeFactory<ExitNodeClientRegistry> externalNodeFactory() {
		return new RemoteNodeFactory<ExitNodeClientRegistry>() {
			@Override
			public ExitNodeToTerminus make(ExitNodeClientRegistry parentModel) {
				ExitNodeToTerminus node = new ExitNodeToTerminus(parentModel);
				node.setRemoteCode(parentModel.registerNode(node));
				return node;
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.TERMINUS;
			}
		};
	}

	private EntryNodeInfo getEntryNode(short nodeCode, boolean autoCreate) {
		EntryNodeInfo info = linkedEntryNodes.get(Short.valueOf(nodeCode));
		if (info == null && autoCreate) {
			info = new EntryNodeInfo();
			linkedEntryNodes.put(Short.valueOf(nodeCode), info);
		}
		return info;
	}

	private EntryNodeInfo removeEntryNode(short nodeCode) {
		return linkedEntryNodes.remove(Short.valueOf(nodeCode));
	}

	// initiating a connection to terminus
	public void linkAttempt(short... entryNodeRelayChain) {
		Short theirTerminus = Short.valueOf(entryNodeRelayChain[1]);

		lock.writeLock().lock();
		try {
			getEntryNode(entryNodeRelayChain[0], true).inProgressPipes.add(theirTerminus);
		} finally {
			lock.writeLock().unlock();
		}
	}

	// entry node terminated the connection attempt
	public boolean linkFailedTheirEnd(ExitNodeToCentralRelay internalLink, short... entryNodeRelayChain) {
		Short theirTerminus = Short.valueOf(entryNodeRelayChain[1]);
		boolean found = false;

		lock.writeLock().lock();
		try {
			EntryNodeInfo info = getEntryNode(entryNodeRelayChain[0], false);
			if (info != null) {
				// first try to stop the pending connection attempt.
				if (info.inProgressPipes.remove(theirTerminus)) {
					info.ignoreOnConnect.add(theirTerminus);
					found = true;
				}

				// then deregister in case the connection succeeded after entry node sent the notification.
				if (info.establishedPipes.remove(theirTerminus)) {
					info.ignoreOnConnect.remove(theirTerminus);
					short ourTerminus = info.ourTerminus.get(theirTerminus).shortValue();
					removeRelayChain(ourTerminus);
					internalLink.getNextNode(ourTerminus).quietClose("Lost connection on source node");
					found = true;
				}
			}

			return found;
		} finally {
			lock.writeLock().unlock();
		}
	}

	// returns true if notification should be sent to entry node
	public boolean linkFailedOurEnd(short... entryNodeRelayChain) {
		Short theirTerminus = Short.valueOf(entryNodeRelayChain[1]);

		lock.writeLock().lock();
		try {
			EntryNodeInfo info = getEntryNode(entryNodeRelayChain[0], false);
			if (info != null) {
				// unmark our connection as "in progress"
				boolean wasInProgress = info.inProgressPipes.remove(theirTerminus);
	
				if (!info.ignoreOnConnect.remove(theirTerminus)) {
					if (!wasInProgress)
						throw new IllegalStateException("Killed a non-existent connection (remote node code: " + entryNodeRelayChain[0] + "," + entryNodeRelayChain[1] + ")");

					return true;
				}
			}

			// entry node already disconnected on us
			return false;
		} finally {
			lock.writeLock().unlock();
		}
	}

	// returns true if notification should be sent to entry node
	public boolean linkEstablished(short ourTerminus, short... entryNodeRelayChain) {
		Short theirTerminus = Short.valueOf(entryNodeRelayChain[1]);

		lock.writeLock().lock();
		try {
			EntryNodeInfo info = getEntryNode(entryNodeRelayChain[0], false);
			if (info != null) {
				// unmark our connection as "in progress"
				boolean wasInProgress = info.inProgressPipes.remove(theirTerminus);

				if (!info.ignoreOnConnect.remove(theirTerminus)) {
					if (!wasInProgress)
						throw new IllegalStateException("Completed a non-existent connection (remote node code: " + entryNodeRelayChain[0] + "," + entryNodeRelayChain[1] + ")");

					// set our relay chain and relay chain reverse mapping
					relayChains.put(Short.valueOf(ourTerminus), entryNodeRelayChain);
					info.establishedPipes.add(theirTerminus);
					info.ourTerminus.put(theirTerminus, Short.valueOf(ourTerminus));
					return true;
				}
			}

			// entry node already disconnected on us
			return false;
		} finally {
			lock.writeLock().unlock();
		}
	}

	// terminus link on entry node or exit node disconnected
	public void linkLost(short ourTerminus, short... entryNodeRelayChain) {
		Short theirTerminus = Short.valueOf(entryNodeRelayChain[1]);

		lock.writeLock().lock();
		try {
			EntryNodeInfo info = getEntryNode(entryNodeRelayChain[0], false);
			boolean wasEstablishedPipe = info != null && info.establishedPipes.remove(theirTerminus);
			boolean hadMapping = info != null && Short.valueOf(ourTerminus).equals(info.ourTerminus.remove(theirTerminus));
			if (!wasEstablishedPipe || !hadMapping)
				throw new IllegalStateException("Inconsistent state in EntryNodeInfo (node code: " + ourTerminus + ")");
		} finally {
			lock.writeLock().unlock();
		}
	}

	// entry node disconnected
	public void linksLost(ExitNodeToCentralRelay internalLink, short entryNode) {
		lock.writeLock().lock();
		try {
			EntryNodeInfo info = removeEntryNode(entryNode);
			if (info != null) {
				// cut terminus connections that relay through the provided entry node
				RemoteNode<ExitNodeClientRegistry> externalLink;
				short ourTerminus;
				for (Short theirTerminus : info.establishedPipes)
					// we're being notified by CENTRAL_RELAY. no need to echo back the cut notification to CENTRAL_RELAY
					if ((externalLink = internalLink.getNextNode(ourTerminus = info.ourTerminus.remove(theirTerminus).shortValue())) != null)
						externalLink.quietClose("Lost connection to entry node");
					else
						throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");
				info.establishedPipes.clear();

				// cut connections in progress
				for (Short theirTerminus : info.inProgressPipes)
					info.ignoreOnConnect.add(theirTerminus);
				info.inProgressPipes.clear();
			}
			// null checks on info in linkFailedOurEnd() and linkEstablished() should
			// ensure that clearing entry node state when ignoreOnConnect is not empty is still safe
		} finally {
			lock.writeLock().unlock();
		}
	}

	public String getTerminusHost() {
		return terminusHost;
	}

	public int getTerminusPort() {
		return terminusPort;
	}

	@Override
	public int getIntermediateHops() {
		// TODO: should receive this in ACCEPTED packet. should be the number of hops to TERMINUS on other side,
		// i.e. 2 because we must go through CENTRAL_RELAY and ENTRY_NODE
		return 2;
	}

	@Override
	public short[] getRelayChain(short ourTerminus) {
		lock.readLock().lock();
		try {
			return relayChains.get(Short.valueOf(ourTerminus));
		} finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public short[] removeRelayChain(short nodeCode) {
		lock.writeLock().lock();
		try {
			return relayChains.remove(Short.valueOf(nodeCode));
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	protected short registerNode(RemoteNode<ExitNodeClientRegistry> node) {
		short nodeCode;
		switch (node.getSessionType()) {
			case UPWARDS_RELAY:
				assert node.getRemoteCode() == ClientType.CENTRAL_RELAY_NODE_CODE : node.getRemoteCode();
				nodeCode = registerUpstream(node);
				break;
			case TERMINUS:
				assert !node.isRemoteCodeSet();
				nodeCode = registerDownstream(node);
				break;
			default:
				throw new IllegalStateException("Invalid session type " + node.getSessionType());
		}
		return nodeCode;
	}

	@Override
	protected RemoteNode<ExitNodeClientRegistry> deregisterNode(SessionType sessionType, short nodeCode) {
		switch (sessionType) {
			case UPWARDS_RELAY:
				assert nodeCode == ClientType.CENTRAL_RELAY_NODE_CODE;
				return deregisterUpstream(nodeCode);
			case TERMINUS:
				assert nodeCode < 0;
				return deregisterDownstream(nodeCode);
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}

	@Override
	public ClientType getRemoteType(SessionType link) {
		switch (link) {
			case UPWARDS_RELAY:
				return ClientType.CENTRAL_RELAY;
			case TERMINUS:
				return null;
			default:
				throw new IllegalStateException("Invalid session type " + link + " for " + getLocalType());
		}
	}

	@Override
	public RemoteNode<ExitNodeClientRegistry> getCentralRelayLink() {
		return getUpstream(ClientType.CENTRAL_RELAY_NODE_CODE);
	}

	@Override
	public void onConnectFailed(SessionType sessionType, Map<String, Object> properties, Throwable ex) {
		switch (sessionType) {
			case UPWARDS_RELAY:
				super.onConnectFailed(sessionType, properties, ex);
				break;
			case TERMINUS: {
				LOG.log(Level.WARNING, "Failed to establish connection with " + RemoteNode.getRemoteTypeString(sessionType, getRemoteType(sessionType)), ex);
				short[] entryNodeRelayChain = (short[]) properties.get("entryNodeRelayChain");
				if (linkFailedOurEnd(entryNodeRelayChain)) {
					getCentralRelayLink().getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8, entryNodeRelayChain[0], LocalRouter.CONTROL_CODE)
						.writeByte(PacketHeaders.PIPE_FAIL)
						.writeShort(entryNodeRelayChain[1])
					.send();
				}
				break;
			}
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}
}

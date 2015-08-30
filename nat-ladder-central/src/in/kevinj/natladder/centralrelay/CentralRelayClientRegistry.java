package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.model.SessionType;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CentralRelayClientRegistry extends LocalRouter<CentralRelayClientRegistry> {
	private final ReadWriteLock exitLock, entryLock;
	private final Map<String, ExitNodeInfo> exitLookupByIdentifier;
	private final Map<Short, ExitNodeInfo> exitLookupByNodeCode;
	private final Map<Short, EntryNodeInfo> entryLookupByNodeCode;

	public CentralRelayClientRegistry() {
		super(ClientType.CENTRAL_RELAY);
		setLocalCode(ClientType.CENTRAL_RELAY_NODE_CODE);
		exitLock = new ReentrantReadWriteLock();
		exitLookupByIdentifier = new HashMap<String, ExitNodeInfo>();
		exitLookupByNodeCode = new HashMap<Short, ExitNodeInfo>();
		entryLock = new ReentrantReadWriteLock();
		entryLookupByNodeCode = new HashMap<Short, EntryNodeInfo>();
	}

	public RemoteNodeFactory<CentralRelayClientRegistry> internalNodeFactory() {
		return new RemoteNodeFactory<CentralRelayClientRegistry>() {
			@Override
			public CentralRelayToBoundaryNode make(CentralRelayClientRegistry parentModel) {
				return new CentralRelayToBoundaryNode(parentModel);
			}

			@Override
			public SessionType typeToMake() {
				return null;
			}
		};
	}

	@Override
	public RemoteNodeFactory<CentralRelayClientRegistry> externalNodeFactory() {
		return null;
	}

	public short registerExitNode(CentralRelayToBoundaryNode node, String identifier, String password, int connectToPort) {
		exitLock.writeLock().lock();
		try {
			if (node.isRemoteCodeSet() && entryLookupByNodeCode.containsKey(Short.valueOf(node.getRemoteCode())))
				return node.getRemoteCode();

			short nodeCode = registerNode(node);
			ExitNodeInfo info = new ExitNodeInfo(identifier, password, connectToPort, nodeCode);
			exitLookupByIdentifier.put(identifier, info);
			exitLookupByNodeCode.put(Short.valueOf(nodeCode), info);
			return nodeCode;
		} finally {
			exitLock.writeLock().unlock();
		}
	}

	public short registerEntryNode(CentralRelayToBoundaryNode node, String identifier, ExitNodeInfo otherEnd) {
		entryLock.writeLock().lock();
		try {
			if (node.isRemoteCodeSet() && entryLookupByNodeCode.containsKey(Short.valueOf(node.getRemoteCode())))
				return node.getRemoteCode();

			short nodeCode = registerNode(node);
			// internally keep track of which exit node is connected to each entry node
			// in case entry node disconnects and we need to notify the exit node who cares.
			entryLookupByNodeCode.put(Short.valueOf(nodeCode), new EntryNodeInfo(identifier, otherEnd.nodeCode));
			// internally keep track of which entry nodes are connected to each exit node
			// in case exit node disconnects and we need to notify the entry nodes who care.
			otherEnd.connectedEntryNodes.add(Short.valueOf(nodeCode));
			return nodeCode;
		} finally {
			entryLock.writeLock().unlock();
		}
	}

	public ExitNodeInfo getExitNode(short nodeCode) {
		exitLock.readLock().lock();
		try {
			return exitLookupByNodeCode.get(Short.valueOf(nodeCode));
		} finally {
			exitLock.readLock().unlock();
		}
	}

	public ExitNodeInfo getExitNode(String identifier) {
		exitLock.readLock().lock();
		try {
			return exitLookupByIdentifier.get(identifier);
		} finally {
			exitLock.readLock().unlock();
		}
	}

	public EntryNodeInfo getEntryNode(short nodeCode) {
		entryLock.readLock().lock();
		try {
			return entryLookupByNodeCode.get(Short.valueOf(nodeCode));
		} finally {
			entryLock.readLock().unlock();
		}
	}

	public ExitNodeInfo deregisterExitNode(SessionType sessionType, short nodeCode) {
		exitLock.writeLock().lock();
		try {
			ExitNodeInfo info = exitLookupByNodeCode.remove(Short.valueOf(nodeCode));
			if (info != null) {
				if (exitLookupByIdentifier.remove(info.identifier) != info)
					throw new IllegalStateException("Inconsistent state in EXIT_ or EXITNAME_ (node code: " + nodeCode + ")");
				deregisterNode(sessionType, nodeCode);
			}
			return info;
		} finally {
			exitLock.writeLock().unlock();
		}
	}

	public ExitNodeInfo deregisterExitNode(SessionType sessionType, String identifier) {
		exitLock.writeLock().lock();
		try {
			ExitNodeInfo info = exitLookupByIdentifier.remove(identifier);
			if (info != null) {
				if (exitLookupByNodeCode.remove(Short.valueOf(info.nodeCode)) != info)
					throw new IllegalStateException("Inconsistent state in EXIT_ or EXITNAME_ (node code: " + info.nodeCode + ")");
				deregisterNode(sessionType, info.nodeCode);
			}
			return info;
		} finally {
			exitLock.writeLock().unlock();
		}
	}

	public EntryNodeInfo deregisterEntryNode(SessionType sessionType, short nodeCode) {
		entryLock.writeLock().lock();
		try {
			EntryNodeInfo info = entryLookupByNodeCode.remove(Short.valueOf(nodeCode));
			if (info != null)
				deregisterNode(sessionType, nodeCode);
			return info;
		} finally {
			entryLock.writeLock().unlock();
		}
	}

	@Override
	public int getIntermediateHops() {
		throw new UnsupportedOperationException(getLocalType() + " does not support raw connections");
	}

	@Override
	public short[] getRelayChain(short nodeCode) {
		throw new UnsupportedOperationException(getLocalType() + " does not support raw connections");
	}

	@Override
	public short[] removeRelayChain(short nodeCode) {
		throw new UnsupportedOperationException(getLocalType() + " does not support raw connections");
	}

	@Override
	protected short registerNode(RemoteNode<CentralRelayClientRegistry> node) {
		short nodeCode;
		switch (node.getSessionType()) {
			case UPWARDS_RELAY:
				assert !node.isRemoteCodeSet();
				nodeCode = registerUpstream(node);
				break;
			case DOWNWARDS_RELAY:
				assert !node.isRemoteCodeSet();
				nodeCode = registerDownstream(node);
				break;
			default:
				throw new IllegalStateException("Invalid session type " + node.getSessionType());
		}
		return nodeCode;
	}

	@Override
	protected RemoteNode<CentralRelayClientRegistry> deregisterNode(SessionType sessionType, short nodeCode) {
		switch (sessionType) {
			case UPWARDS_RELAY:
				assert nodeCode > 0;
				return deregisterUpstream(nodeCode);
			case DOWNWARDS_RELAY:
				assert nodeCode < 0;
				return deregisterDownstream(nodeCode);
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}

	@Override
	public ClientType getRemoteType(SessionType link) {
		switch (link) {
			case DOWNWARDS_RELAY:
				return ClientType.EXIT_NODE;
			case UPWARDS_RELAY:
				return ClientType.ENTRY_NODE;
			default:
				throw new IllegalStateException("Invalid session type " + link + " for " + getLocalType());
		}
	}

	@Override
	public RemoteNode<CentralRelayClientRegistry> getCentralRelayLink() {
		throw new UnsupportedOperationException(getLocalType() + " has no links to " + ClientType.CENTRAL_RELAY);
	}

	@Override
	public void onConnectFailed(SessionType sessionType, Map<String, Object> properties, Throwable ex) {
		throw new UnsupportedOperationException(getLocalType() + " initiates no connections");
	}
}

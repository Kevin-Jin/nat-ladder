package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.netimpl.BufferCache;
import in.kevinj.natladder.common.netimpl.ClientManager;
import in.kevinj.natladder.common.util.ScheduledHashedWheelExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;

public abstract class LocalRouter {
	public static final short CONTROL_CODE = 0;

	private final ClientType localType;
	private ClientManager clientManager;

	private final Map<String, Object> properties;
	private final SortedMap<Short, RemoteNode> upstreamNodes;
	private final SortedMap<Short, RemoteNode> downstreamNodes;

	private final BufferCache bufferCache;
	private final ScheduledExecutorService wheelTimer;

	private boolean isNodeCodeSet;
	private short thisNodeCode;

	public LocalRouter(ClientType localType) {
		this.localType = localType;
		properties = new HashMap<String, Object>();
		upstreamNodes = new TreeMap<Short, RemoteNode>();
		downstreamNodes = new TreeMap<Short, RemoteNode>();
		bufferCache = new BufferCache();
		wheelTimer = new ScheduledHashedWheelExecutor();
	}

	public void setClientManager(ClientManager manager) {
		this.clientManager = manager;
	}

	public ClientManager getClientManager() {
		return clientManager;
	}

	public BufferCache getBufferCache() {
		return bufferCache;
	}

	public ScheduledExecutorService getWheelTimer() {
		return wheelTimer;
	}

	public short getLocalCode() {
		if (!isNodeCodeSet)
			throw new IllegalStateException("Invalid local node code null");

		return thisNodeCode;
	}

	public void setLocalCode(short code) {
		if (isNodeCodeSet)
			throw new IllegalStateException("Invalid local node code " + code + " (to replace " + getLocalCode() + ")");

		thisNodeCode = code;
		isNodeCodeSet = true;
	}

	public ClientType getLocalType() {
		return localType;
	}

	// TODO: not type-safe and a code smell. replace functionality with polymorphism somehow.
	public boolean setProperty(String prop, Object value) {
		return properties.put(prop, value) != null;
	}

	@SuppressWarnings("unchecked")
	public void extendProperty(String prop, Object value) {
		Object existing = properties.get(prop);
		if (existing == null)
			existing = new ArrayList<Object>();
		else if (!(existing instanceof Collection))
			throw new IllegalStateException(prop + " is not a list property.");

		((Collection<Object>) existing).add(value);
	}

	public Object getProperty(String prop) {
		return properties.get(prop);
	}

	public Object removeProperty(String prop) {
		return properties.remove(prop);
	}

	public RemoteNode getUpstream(short nodeCode) {
		return upstreamNodes.get(Short.valueOf(nodeCode));
	}

	public RemoteNode getDownstream(short nodeCode) {
		return downstreamNodes.get(Short.valueOf(nodeCode));
	}

	private short registerNode(SortedMap<Short, RemoteNode> nodes, RemoteNode node, int autoIncrement) {
		assert node.isRemoteCodeSet() ^ getLocalType() == ClientType.CENTRAL_RELAY : ((node.isRemoteCodeSet() ? node.getRemoteCode() : "null") + " " + getLocalType());

		short nodeCode;
		if (node.isRemoteCodeSet())
			nodeCode = node.getRemoteCode();
		else
			nodeCode = (short) ((nodes.isEmpty() ? 0 : nodes.lastKey().shortValue()) + autoIncrement);
		nodes.put(Short.valueOf(nodeCode), node);
		return nodeCode;
	}

	public synchronized short registerNode(RemoteNode node) {
		short nodeCode;
		switch (node.getSessionType()) {
			case UPWARDS_RELAY:
				nodeCode = registerNode(upstreamNodes, node, 1);
				break;
			case DOWNWARDS_RELAY:
				nodeCode = registerNode(downstreamNodes, node, -1);
				break;
			case TERMINUS:
				switch (getLocalType()) {
					case ENTRY_NODE:
						nodeCode = registerNode(upstreamNodes, node, 1);
						break;
					case EXIT_NODE:
						nodeCode = registerNode(downstreamNodes, node, -1);
						break;
					default:
						throw new IllegalStateException("Invalid client type " + getLocalType());
				}
				break;
			default:
				throw new IllegalStateException("Invalid session type " + node.getSessionType());
		}
		return nodeCode;
	}

	private RemoteNode deregisterNode(SortedMap<Short, RemoteNode> nodes, short nodeCode) {
		return nodes.remove(Short.valueOf(nodeCode));
	}

	public synchronized RemoteNode deregisterNode(SessionType sessionType, short nodeCode) {
		switch (sessionType) {
			case UPWARDS_RELAY:
				return deregisterNode(upstreamNodes, nodeCode);
			case DOWNWARDS_RELAY:
				return deregisterNode(downstreamNodes, nodeCode);
			case TERMINUS:
				switch (getLocalType()) {
					case ENTRY_NODE:
						return deregisterNode(upstreamNodes, nodeCode);
					case EXIT_NODE:
						return deregisterNode(downstreamNodes, nodeCode);
					default:
						throw new IllegalStateException("Invalid client type " + getLocalType());
				}
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}

	public synchronized void deregisterNode(RemoteNode node) {
		RemoteNode removed = deregisterNode(node.getSessionType(), node.getRemoteCode());
		if (removed != node)
			throw new IllegalStateException("Deregistered " + removed + " instead of " + node);
	}
}

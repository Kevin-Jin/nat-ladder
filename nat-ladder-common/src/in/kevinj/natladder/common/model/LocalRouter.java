package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.netimpl.BufferCache;
import in.kevinj.natladder.common.netimpl.ClientManager;
import in.kevinj.natladder.common.util.ScheduledHashedWheelExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class LocalRouter {
	protected static final Logger LOG = Logger.getLogger(LocalRouter.class.getName());

	public static final short CONTROL_CODE = 0;

	private final ClientType localType;
	private ClientManager clientManager;

	private final Map<String, Object> properties;
	private final SortedMap<Short, RemoteNode> upstreamNodes;
	private final Queue<Short> upstreamNodeCodeGaps;
	private final SortedMap<Short, RemoteNode> downstreamNodes;
	private final Queue<Short> downstreamNodeCodeGaps;

	private final BufferCache bufferCache;
	private final ScheduledExecutorService wheelTimer;

	private boolean isNodeCodeSet;
	private short thisNodeCode;

	public LocalRouter(ClientType localType) {
		this.localType = localType;
		properties = new HashMap<String, Object>();
		// upstream node codes are positive. lastKey() should return the highest magnitude positive number.
		upstreamNodes = new TreeMap<Short, RemoteNode>(new Comparator<Short>() {
			@Override
			public int compare(Short o1, Short o2) {
				return o1.compareTo(o2);
			}
		});
		upstreamNodeCodeGaps = new LinkedList<Short>();
		// downstream node codes are negative. lastKey() should return the highest magnitude negative number.
		downstreamNodes = new TreeMap<Short, RemoteNode>(Collections.reverseOrder());
		downstreamNodeCodeGaps = new LinkedList<Short>();
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

		LOG.log(Level.INFO, "Serving as {0} ({1})", new Object[] { getLocalType(), getLocalCode() });
	}

	public ClientType getLocalType() {
		return localType;
	}

	// TODO: not type-safe and a code smell. replace functionality with polymorphism somehow.
	public boolean setProperty(String prop, Object value) {
		return properties.put(prop, value) != null;
	}

	@SuppressWarnings("unchecked")
	public void extendProperty(String prop, Object... values) {
		Object existing = properties.get(prop);
		if (existing == null) {
			existing = new ArrayList<Object>();
			properties.put(prop, existing);
		} else if (!(existing instanceof Collection)) {
			throw new IllegalStateException(prop + " is not a list property.");
		}

		((Collection<Object>) existing).addAll(Arrays.asList(values));
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

	private synchronized short registerNode(SortedMap<Short, RemoteNode> nodes, Queue<Short> gaps, RemoteNode node, short autoIncrement) {
		// we are responsible for auto generating a node code if we are CENTRAL_RELAY
		// or if we are entry/exit node and the link is to a TERMINUS
		assert !node.isRemoteCodeSet() == (getLocalType() == ClientType.CENTRAL_RELAY || node.getSessionType() == SessionType.TERMINUS) : ((node.isRemoteCodeSet() ? node.getRemoteCode() : "null") + " " + getLocalType() + " " + node.getSessionType());

		short nodeCode;
		if (node.isRemoteCodeSet())
			if (!nodes.containsKey(Short.valueOf(node.getRemoteCode())))
				nodeCode = node.getRemoteCode();
			else
				throw new IllegalStateException(node.getRemoteCode() + " is already registered.");
		else if (!gaps.isEmpty())
			nodeCode = gaps.remove();
		else if (nodes.isEmpty())
			nodeCode = autoIncrement;
		else if (Math.signum((nodeCode = nodes.lastKey().shortValue())) == Math.signum(nodeCode + autoIncrement))
			nodeCode += autoIncrement;
		else
			throw new IllegalStateException("LocalRouter is at capacity");
		nodes.put(Short.valueOf(nodeCode), node);
		return nodeCode;
	}

	public short registerNode(RemoteNode node) {
		short nodeCode;
		switch (node.getSessionType()) {
			case UPWARDS_RELAY:
				assert !node.isRemoteCodeSet() || getLocalType() == ClientType.EXIT_NODE && node.getRemoteCode() == ClientType.CENTRAL_RELAY_NODE_CODE : getLocalType() + " " + node.getRemoteCode();
				nodeCode = registerNode(upstreamNodes, upstreamNodeCodeGaps, node, (short) 1);
				break;
			case DOWNWARDS_RELAY:
				assert !node.isRemoteCodeSet() || getLocalType() == ClientType.ENTRY_NODE && node.getRemoteCode() == ClientType.CENTRAL_RELAY_NODE_CODE : getLocalType() + " " + node.getRemoteCode();
				nodeCode = registerNode(downstreamNodes, downstreamNodeCodeGaps, node, (short) -1);
				break;
			case TERMINUS:
				switch (getLocalType()) {
					case ENTRY_NODE:
						assert !node.isRemoteCodeSet() : node.isRemoteCodeSet();
						nodeCode = registerNode(upstreamNodes, upstreamNodeCodeGaps, node, (short) 1);
						break;
					case EXIT_NODE:
						assert !node.isRemoteCodeSet() : node.isRemoteCodeSet();
						nodeCode = registerNode(downstreamNodes, downstreamNodeCodeGaps, node, (short) -1);
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

	private synchronized RemoteNode deregisterNode(SortedMap<Short, RemoteNode> nodes, Queue<Short> gaps, short nodeCode) {
		Short oNodeCode = Short.valueOf(nodeCode);
		// don't add nodeCode to gaps if auto increment will take care of it
		if (!nodes.isEmpty() && nodes.comparator().compare(oNodeCode, nodes.lastKey()) < 0)
			gaps.add(oNodeCode);
		return nodes.remove(oNodeCode);
	}

	public RemoteNode deregisterNode(SessionType sessionType, short nodeCode) {
		switch (sessionType) {
			case UPWARDS_RELAY:
				assert getLocalType() == ClientType.CENTRAL_RELAY && nodeCode > 0 || getLocalType() == ClientType.EXIT_NODE && nodeCode == ClientType.CENTRAL_RELAY_NODE_CODE;
				return deregisterNode(upstreamNodes, upstreamNodeCodeGaps, nodeCode);
			case DOWNWARDS_RELAY:
				assert getLocalType() == ClientType.CENTRAL_RELAY && nodeCode < 0 || getLocalType() == ClientType.ENTRY_NODE && nodeCode == ClientType.CENTRAL_RELAY_NODE_CODE;
				return deregisterNode(downstreamNodes, downstreamNodeCodeGaps, nodeCode);
			case TERMINUS:
				switch (getLocalType()) {
					case ENTRY_NODE:
						assert nodeCode > 0;
						return deregisterNode(upstreamNodes, upstreamNodeCodeGaps, nodeCode);
					case EXIT_NODE:
						assert nodeCode < 0;
						return deregisterNode(downstreamNodes, downstreamNodeCodeGaps, nodeCode);
					default:
						throw new IllegalStateException("Invalid client type " + getLocalType());
				}
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}

	public void deregisterNode(RemoteNode node) {
		RemoteNode removed = deregisterNode(node.getSessionType(), node.getRemoteCode());
		if (removed != node)
			throw new IllegalStateException("Deregistered " + removed + " instead of " + node);
	}

	public void dispose() {
		getWheelTimer().shutdownNow();
	}
}

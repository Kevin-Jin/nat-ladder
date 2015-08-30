package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.netimpl.BufferCache;
import in.kevinj.natladder.common.netimpl.ClientManager;
import in.kevinj.natladder.common.util.ScheduledHashedWheelExecutor;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class LocalRouter<T extends LocalRouter<T>> {
	protected static final Logger LOG = Logger.getLogger(LocalRouter.class.getName());

	public static final short CONTROL_CODE = 0;

	private final ClientType localType;
	private ClientManager<T> clientManager;

	private final SortedMap<Short, RemoteNode<T>> upstreamNodes;
	private final Queue<Short> upstreamNodeCodeGaps;
	private final SortedMap<Short, RemoteNode<T>> downstreamNodes;
	private final Queue<Short> downstreamNodeCodeGaps;

	private final BufferCache bufferCache;
	private final ScheduledExecutorService wheelTimer;

	private boolean isNodeCodeSet;
	private short thisNodeCode;

	public LocalRouter(ClientType localType) {
		this.localType = localType;
		// upstream node codes are positive. lastKey() should return the highest magnitude positive number.
		upstreamNodes = new TreeMap<Short, RemoteNode<T>>(new Comparator<Short>() {
			@Override
			public int compare(Short o1, Short o2) {
				return o1.compareTo(o2);
			}
		});
		upstreamNodeCodeGaps = new LinkedList<Short>();
		// downstream node codes are negative. lastKey() should return the highest magnitude negative number.
		downstreamNodes = new TreeMap<Short, RemoteNode<T>>(Collections.reverseOrder());
		downstreamNodeCodeGaps = new LinkedList<Short>();
		bufferCache = new BufferCache();
		wheelTimer = new ScheduledHashedWheelExecutor();
	}

	public abstract RemoteNodeFactory<T> internalNodeFactory();

	public abstract RemoteNodeFactory<T> externalNodeFactory();

	public abstract int getIntermediateHops();

	public abstract short[] getRelayChain(short ourTerminus);

	public abstract short[] removeRelayChain(short ourTerminus);

	public void setClientManager(ClientManager<T> manager) {
		this.clientManager = manager;
	}

	public ClientManager<T> getClientManager() {
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

	public RemoteNode<T> getUpstream(short nodeCode) {
		return upstreamNodes.get(Short.valueOf(nodeCode));
	}

	public RemoteNode<T> getDownstream(short nodeCode) {
		return downstreamNodes.get(Short.valueOf(nodeCode));
	}

	private synchronized short registerNode(SortedMap<Short, RemoteNode<T>> nodes, Queue<Short> gaps, RemoteNode<T> node, short autoIncrement) {
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

	protected short registerUpstream(RemoteNode<T> node) {
		return registerNode(upstreamNodes, upstreamNodeCodeGaps, node, (short) 1);
	}

	protected short registerDownstream(RemoteNode<T> node) {
		return registerNode(downstreamNodes, downstreamNodeCodeGaps, node, (short) -1);
	}

	protected abstract short registerNode(RemoteNode<T> node);

	protected synchronized RemoteNode<T> deregisterNode(SortedMap<Short, RemoteNode<T>> nodes, Queue<Short> gaps, short nodeCode) {
		Short oNodeCode = Short.valueOf(nodeCode);
		// don't add nodeCode to gaps if auto increment will take care of it
		if (!nodes.isEmpty() && nodes.comparator().compare(oNodeCode, nodes.lastKey()) < 0)
			gaps.add(oNodeCode);
		return nodes.remove(oNodeCode);
	}

	protected RemoteNode<T> deregisterUpstream(short nodeCode) {
		return deregisterNode(upstreamNodes, upstreamNodeCodeGaps, nodeCode);
	}

	protected RemoteNode<T> deregisterDownstream(short nodeCode) {
		return deregisterNode(downstreamNodes, downstreamNodeCodeGaps, nodeCode);
	}

	protected abstract RemoteNode<T> deregisterNode(SessionType sessionType, short nodeCode);

	protected void deregisterNode(RemoteNode<T> node) {
		RemoteNode<T> removed = deregisterNode(node.getSessionType(), node.getRemoteCode());
		if (removed != node)
			throw new IllegalStateException("Deregistered " + removed + " instead of " + node);
	}

	public abstract ClientType getRemoteType(SessionType link);

	public abstract RemoteNode<T> getCentralRelayLink();

	public void onConnectFailed(SessionType sessionType, Map<String, Object> properties, Throwable ex) {
		getClientManager().close("Failed to establish connection with " + RemoteNode.getRemoteTypeString(sessionType, getRemoteType(sessionType)), ex);
	}

	public void dispose() {
		getWheelTimer().shutdownNow();
	}
}

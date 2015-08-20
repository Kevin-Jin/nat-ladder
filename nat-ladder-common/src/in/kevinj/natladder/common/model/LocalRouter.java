package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.netimpl.BufferCache;
import in.kevinj.natladder.common.netimpl.ClientSession;
import in.kevinj.natladder.common.util.ScheduledHashedWheelExecutor;

import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;

public abstract class LocalRouter {
	public static final short CONTROL_CODE = 0;

	private final ClientType localType;

	private final SortedMap<Short, ClientSession> upstreamNodes;
	private final SortedMap<Short, ClientSession> downstreamNodes;

	private final BufferCache bufferCache;
	private final ScheduledExecutorService wheelTimer;

	private boolean isNodeCodeSet;
	private short thisNodeCode;

	public LocalRouter(ClientType localType) {
		this.localType = localType;
		upstreamNodes = new TreeMap<Short, ClientSession>();
		downstreamNodes = new TreeMap<Short, ClientSession>();
		bufferCache = new BufferCache();
		wheelTimer = new ScheduledHashedWheelExecutor();
	}

	public BufferCache getBufferCache() {
		return bufferCache;
	}

	public ScheduledExecutorService getWheelTimer() {
		return wheelTimer;
	}

	public short getLocalCode() {
		if (!isNodeCodeSet)
			throw new IllegalStateException("Invalid local node code.");

		return thisNodeCode;
	}

	public void setLocalCode(short code) {
		if (isNodeCodeSet)
			throw new IllegalStateException("Invalid local node code.");

		thisNodeCode = code;
		isNodeCodeSet = true;
	}

	public ClientType getLocalType() {
		return localType;
	}

	public abstract short[] getRelayChain(short fromCode);
	public abstract void removeFromRelayTable(short... relayChain);

	public ClientSession getUpstream(short nodeCode) {
		return upstreamNodes.get(Short.valueOf(nodeCode));
	}

	public ClientSession getDownstream(short nodeCode) {
		return downstreamNodes.get(Short.valueOf(nodeCode));
	}

	private short registerNode(SortedMap<Short, ClientSession> nodes, RemoteNode node, int autoIncrement) {
		short nodeCode;
		assert node.isRemoteCodeSet() ^ getLocalType() == ClientType.CENTRAL_RELAY;
		if (node.isRemoteCodeSet())
			nodeCode = node.getRemoteCode();
		else
			nodeCode = (short) ((nodes.isEmpty() ? 0 : nodes.lastKey().shortValue()) + autoIncrement);
		nodes.put(Short.valueOf(nodeCode), node.getClientSession());
		return nodeCode;
	}

	// FIXME: needs to be called by entry node after receiving terminus
	// connection and assigning it an unused entry nodeCode, i.e.
	// registerNode(connection) --> [ (new nodeCode), (upstream) ]
	// FIXME: needs to be called by exit node after receiving connection
	// and nodeCode from entry + relay, connecting to terminus, and and
	// assigning new terminus connection an unused exit nodeCode, i.e.
	// registerNode(connection) --> [ (new nodeCode), (downstream) ]
	// FIXME: needs to be also be integrated with relay table.
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
						throw new IllegalStateException("Invalid session type");
				}
				break;
			default:
				throw new IllegalStateException("Invalid session type");
		}
		return nodeCode;
	}
}

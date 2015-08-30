package in.kevinj.natladder.entrynode;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.model.SessionType;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class EntryNodeClientRegistry extends LocalRouter<EntryNodeClientRegistry> {
	private volatile short exitNodeCode;
	private final ConcurrentMap<Short, short[]> relayChains;

	public EntryNodeClientRegistry(ClientType localType) {
		super(localType);
		relayChains = new ConcurrentHashMap<Short, short[]>();
	}

	@Override
	public RemoteNodeFactory<EntryNodeClientRegistry> internalNodeFactory() {
		return new RemoteNodeFactory<EntryNodeClientRegistry>() {
			@Override
			public EntryNodeToCentralRelay make(EntryNodeClientRegistry parentModel) {
				return new EntryNodeToCentralRelay(parentModel, SessionType.DOWNWARDS_RELAY);
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.DOWNWARDS_RELAY;
			}
		};
	}

	@Override
	public RemoteNodeFactory<EntryNodeClientRegistry> externalNodeFactory() {
		return new RemoteNodeFactory<EntryNodeClientRegistry>() {
			@Override
			public EntryNodeToTerminus make(EntryNodeClientRegistry parentModel) {
				EntryNodeToTerminus node = new EntryNodeToTerminus(parentModel);
				node.setRemoteCode(parentModel.registerNode(node));
				return node;
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.TERMINUS;
			}
		};
	}

	public void setExitNodeCode(short nodeCode) {
		exitNodeCode = nodeCode;
	}

	public void setRelayChain(short ourTerminus, short... relayChain) {
		assert relayChain.length == getIntermediateHops();

		relayChains.put(Short.valueOf(ourTerminus), relayChain);
	}

	public short getExitNodeCode() {
		return exitNodeCode;
	}

	@Override
	public int getIntermediateHops() {
		// TODO: should receive this in ACCEPTED packet. should be the number of hops to TERMINUS on other side,
		// i.e. 2 because we must go through CENTRAL_RELAY and EXIT_NODE
		return 2;
	}

	@Override
	public short[] getRelayChain(short nodeCode) {
		return relayChains.get(Short.valueOf(nodeCode));
	}

	@Override
	public short[] removeRelayChain(short nodeCode) {
		return relayChains.remove(Short.valueOf(nodeCode));
	}

	@Override
	protected short registerNode(RemoteNode<EntryNodeClientRegistry> node) {
		short nodeCode;
		switch (node.getSessionType()) {
			case DOWNWARDS_RELAY:
				assert node.getRemoteCode() == ClientType.CENTRAL_RELAY_NODE_CODE : node.getRemoteCode();
				nodeCode = registerDownstream(node);
				break;
			case TERMINUS:
				assert !node.isRemoteCodeSet();
				nodeCode = registerUpstream(node);
				break;
			default:
				throw new IllegalStateException("Invalid session type " + node.getSessionType());
		}
		return nodeCode;
	}

	@Override
	protected RemoteNode<EntryNodeClientRegistry> deregisterNode(SessionType sessionType, short nodeCode) {
		switch (sessionType) {
			case DOWNWARDS_RELAY:
				assert nodeCode == ClientType.CENTRAL_RELAY_NODE_CODE;
				return deregisterDownstream(nodeCode);
			case TERMINUS:
				assert nodeCode > 0;
				return deregisterUpstream(nodeCode);
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}

	@Override
	public ClientType getRemoteType(SessionType link) {
		switch (link) {
			case DOWNWARDS_RELAY:
				return ClientType.CENTRAL_RELAY;
			case TERMINUS:
				return null;
			default:
				throw new IllegalStateException("Invalid session type " + link + " for " + getLocalType());
		}
	}

	@Override
	public RemoteNode<EntryNodeClientRegistry> getCentralRelayLink() {
		return getDownstream(ClientType.CENTRAL_RELAY_NODE_CODE);
	}
}

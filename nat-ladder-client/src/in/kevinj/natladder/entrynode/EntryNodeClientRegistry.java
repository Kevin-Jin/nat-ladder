package in.kevinj.natladder.entrynode;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.model.SessionType;

public class EntryNodeClientRegistry extends LocalRouter<EntryNodeClientRegistry> {
	public EntryNodeClientRegistry(ClientType localType) {
		super(localType);
	}

	@Override
	public RemoteNodeFactory<EntryNodeClientRegistry> internalNodeFactory() {
		return new RemoteNodeFactory<EntryNodeClientRegistry>() {
			@Override
			public EntryNodeInternalClient make(EntryNodeClientRegistry parentModel) {
				return new EntryNodeInternalClient(parentModel, SessionType.DOWNWARDS_RELAY);
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
			public EntryNodeExternalClient make(EntryNodeClientRegistry parentModel) {
				EntryNodeExternalClient node = new EntryNodeExternalClient(parentModel);
				node.setRemoteCode(parentModel.registerNode(node));
				return node;
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.TERMINUS;
			}
		};
	}

	@Override
	public short registerNode(RemoteNode<EntryNodeClientRegistry> node) {
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
	public RemoteNode<EntryNodeClientRegistry> deregisterNode(SessionType sessionType, short nodeCode) {
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

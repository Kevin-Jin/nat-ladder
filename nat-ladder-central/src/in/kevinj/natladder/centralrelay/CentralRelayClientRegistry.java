package in.kevinj.natladder.centralrelay;

import java.util.Map;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.model.SessionType;

public class CentralRelayClientRegistry extends LocalRouter<CentralRelayClientRegistry> {
	public CentralRelayClientRegistry() {
		super(ClientType.CENTRAL_RELAY);
		setLocalCode(ClientType.CENTRAL_RELAY_NODE_CODE);
	}

	public RemoteNodeFactory<CentralRelayClientRegistry> internalNodeFactory() {
		return new RemoteNodeFactory<CentralRelayClientRegistry>() {
			@Override
			public CentralRelayInternalClient make(CentralRelayClientRegistry parentModel) {
				return new CentralRelayInternalClient(parentModel);
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

	@Override
	public short registerNode(RemoteNode<CentralRelayClientRegistry> node) {
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
	public RemoteNode<CentralRelayClientRegistry> deregisterNode(SessionType sessionType, short nodeCode) {
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

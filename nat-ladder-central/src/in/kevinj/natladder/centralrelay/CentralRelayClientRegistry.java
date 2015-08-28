package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
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
}

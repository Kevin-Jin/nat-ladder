package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.model.SessionType;

public class ExitNodeClientRegistry extends LocalRouter<ExitNodeClientRegistry> {
	public ExitNodeClientRegistry(ClientType localType) {
		super(localType);
	}

	@Override
	public RemoteNodeFactory<ExitNodeClientRegistry> internalNodeFactory() {
		return new RemoteNodeFactory<ExitNodeClientRegistry>() {
			@Override
			public ExitNodeInternalClient make(ExitNodeClientRegistry parentModel) {
				return new ExitNodeInternalClient(parentModel, SessionType.UPWARDS_RELAY);
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
			public ExitNodeExternalClient make(ExitNodeClientRegistry parentModel) {
				ExitNodeExternalClient node = new ExitNodeExternalClient(parentModel);
				node.setRemoteCode(parentModel.registerNode(node));
				return node;
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.TERMINUS;
			}
		};
	}
}

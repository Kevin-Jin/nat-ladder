package in.kevinj.natladder.entrynode;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
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
}

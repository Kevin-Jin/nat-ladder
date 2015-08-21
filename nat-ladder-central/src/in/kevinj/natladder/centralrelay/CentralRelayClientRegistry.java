package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;

public class CentralRelayClientRegistry extends LocalRouter {
	public CentralRelayClientRegistry() {
		super(ClientType.CENTRAL_RELAY);
		setLocalCode(ClientType.CENTRAL_RELAY_NODE_CODE);
	}
}

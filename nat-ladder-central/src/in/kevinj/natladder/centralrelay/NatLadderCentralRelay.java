package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.netimpl.ClientManager;
import in.kevinj.natladder.common.netimpl.ClientManagerNio;

import java.util.Collections;

public class NatLadderCentralRelay {
	private static final int CENTRAL_RELAY_PORT = 3425;

	public static void main(String[] args) {
		CentralRelayClientRegistry state = new CentralRelayClientRegistry();
		ClientManager eventLoop = new ClientManagerNio(state);
		state.setClientManager(eventLoop);
		eventLoop.listen(RemoteRouter.internalNodeFactory, CENTRAL_RELAY_PORT, Collections.<String, Object>emptyMap());
	}
}

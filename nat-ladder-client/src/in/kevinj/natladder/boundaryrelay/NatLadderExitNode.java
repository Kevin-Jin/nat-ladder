package in.kevinj.natladder.boundaryrelay;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.netimpl.ClientManager;
import in.kevinj.natladder.common.netimpl.ClientManagerNio;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class NatLadderExitNode {
	private static final String CENTRAL_RELAY_HOST = "kevinj.in";
	private static final int CENTRAL_RELAY_PORT = 3425;
	private static final int TERMINUS_PORT = 8080;

	public static void main(String[] args) {
		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("identifier", "test");
		properties.put("password", "test");
		properties.put("connectToPort", Integer.valueOf(TERMINUS_PORT));

		BoundaryRelayClientRegistry state = new BoundaryRelayClientRegistry(ClientType.EXIT_NODE);
		// TODO: should receive this in ACCEPTED packet. should be the number of hops to TERMINUS on other side,
		// i.e. 2 because we must go through CENTRAL_RELAY and ENTRY_NODE
		state.setProperty("RELAYCHAIN_DEFAULT", Integer.valueOf(2));
		state.setProperty("terminusHost", "localhost");
		state.setProperty("terminusPort", Integer.valueOf(TERMINUS_PORT));
		ClientManager eventLoop = new ClientManagerNio(state);
		state.setClientManager(eventLoop);
		eventLoop.connect(RemoteRouter.upwardsRelayFactory, CENTRAL_RELAY_HOST, CENTRAL_RELAY_PORT, Collections.unmodifiableMap(properties));
	}
}

package in.kevinj.natladder.boundaryrelay;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.netimpl.ClientManager;
import in.kevinj.natladder.common.netimpl.ClientManagerNio;
import in.kevinj.natladder.common.util.CliHelper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class NatLadderEntryNode {
	private static final String CENTRAL_RELAY_HOST = "kevinj.in";
	private static final int CENTRAL_RELAY_PORT = 3425;

	public static void main(String[] args) {
		String centralRelayHost = CliHelper.tryGet(args, 0, CENTRAL_RELAY_HOST);
		int centralRelayPort = CliHelper.tryParse(args, 1, CENTRAL_RELAY_PORT);
		String identifier = CliHelper.tryGet(args, 2, "test");
		String password = CliHelper.tryGet(args, 3, "test");

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("identifier", identifier);
		properties.put("password", password);

		BoundaryRelayClientRegistry state = new BoundaryRelayClientRegistry(ClientType.ENTRY_NODE);
		// TODO: should receive this in ACCEPTED packet. should be the number of hops to TERMINUS on other side,
		// i.e. 2 because we must go through CENTRAL_RELAY and EXIT_NODE
		state.setProperty("RELAYCHAIN_DEFAULT", Integer.valueOf(2));
		ClientManager eventLoop = new ClientManagerNio(state);
		state.setClientManager(eventLoop);
		eventLoop.connect(RemoteRouter.downwardsRelayFactory, centralRelayHost, centralRelayPort, Collections.unmodifiableMap(properties));
	}
}

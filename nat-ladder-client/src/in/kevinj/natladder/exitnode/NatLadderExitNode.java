package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.netimpl.ClientManager;
import in.kevinj.natladder.common.netimpl.ClientManagerNio;
import in.kevinj.natladder.common.util.CliHelper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class NatLadderExitNode {
	private static final String CENTRAL_RELAY_HOST = "kevinj.in";
	private static final int CENTRAL_RELAY_PORT = 3425;
	private static final String TERMINUS_HOST = "localhost";
	private static final int TERMINUS_PORT = 8080;

	public static void main(String[] args) {
		String centralRelayHost = CliHelper.tryGet(args, 0, CENTRAL_RELAY_HOST);
		int centralRelayPort = CliHelper.tryParse(args, 1, CENTRAL_RELAY_PORT);
		String identifier = CliHelper.tryGet(args, 2, "test");
		String password = CliHelper.tryGet(args, 3, "test");
		String terminusHost = CliHelper.tryGet(args, 4, TERMINUS_HOST);
		int terminusPort = CliHelper.tryParse(args, 5, TERMINUS_PORT);

		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put("identifier", identifier);
		properties.put("password", password);
		properties.put("connectToPort", Integer.valueOf(terminusPort));

		ExitNodeClientRegistry state = new ExitNodeClientRegistry(ClientType.EXIT_NODE);
		// TODO: should receive this in ACCEPTED packet. should be the number of hops to TERMINUS on other side,
		// i.e. 2 because we must go through CENTRAL_RELAY and ENTRY_NODE
		state.setProperty("RELAYCHAIN_DEFAULT", Integer.valueOf(2));
		state.setProperty("terminusHost", terminusHost);
		state.setProperty("terminusPort", Integer.valueOf(terminusPort));
		ClientManager<ExitNodeClientRegistry> eventLoop = new ClientManagerNio<ExitNodeClientRegistry>(state);
		state.setClientManager(eventLoop);
		eventLoop.connect(state.internalNodeFactory(), centralRelayHost, centralRelayPort, Collections.unmodifiableMap(properties));
	}
}

package in.kevinj.natladder.entrynode;

import in.kevinj.natladder.common.model.ClientType;
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

		EntryNodeClientRegistry state = new EntryNodeClientRegistry(ClientType.ENTRY_NODE);
		ClientManager<EntryNodeClientRegistry> eventLoop = new ClientManagerNio<EntryNodeClientRegistry>(state);
		state.setClientManager(eventLoop);
		eventLoop.connect(state.internalNodeFactory(), centralRelayHost, centralRelayPort, Collections.unmodifiableMap(properties));
	}
}

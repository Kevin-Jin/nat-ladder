package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.netimpl.ClientManager;
import in.kevinj.natladder.common.netimpl.ClientManagerNio;
import in.kevinj.natladder.common.util.CliHelper;

import java.util.Collections;

public class NatLadderCentralRelay {
	private static final String CENTRAL_RELAY_HOST = "0.0.0.0";
	private static final int CENTRAL_RELAY_PORT = 3425;

	public static void main(String[] args) {
		String centralRelayHost = CliHelper.tryGet(args, 0, CENTRAL_RELAY_HOST);
		int centralRelayPort = CliHelper.tryParse(args, 1, CENTRAL_RELAY_PORT);

		CentralRelayClientRegistry state = new CentralRelayClientRegistry();
		ClientManager<CentralRelayClientRegistry> eventLoop = new ClientManagerNio<CentralRelayClientRegistry>(state);
		state.setClientManager(eventLoop);
		eventLoop.listen(state.internalNodeFactory(), centralRelayHost, centralRelayPort, Collections.<String, Object>emptyMap());
	}
}

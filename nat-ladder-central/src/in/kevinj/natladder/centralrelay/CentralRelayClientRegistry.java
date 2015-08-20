package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;

public class CentralRelayClientRegistry extends LocalRouter {
	public CentralRelayClientRegistry() {
		super(ClientType.CENTRAL_RELAY);
		setLocalCode(ClientType.CENTRAL_RELAY_NODE_CODE);
	}

	@Override
	public short[] getRelayChain(short fromCode) {
		// FIXME: if fromCode is a EXIT_NODE, return { RELAY'S_CODE_TO_ENTRY_NODE }
		// FIXME: if fromCode is an ENTRY_NODE, return { RELAY'S_CODE_TO_EXIT_NODE }
		throw new UnsupportedOperationException("Not yet implemented");
	}

	@Override
	public void removeFromRelayTable(short... relayChain) {
		// FIXME: implement
		throw new UnsupportedOperationException("Not yet implemented");
	}
}

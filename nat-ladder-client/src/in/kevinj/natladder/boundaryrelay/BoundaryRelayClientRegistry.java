package in.kevinj.natladder.boundaryrelay;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;

public class BoundaryRelayClientRegistry extends LocalRouter {
	public BoundaryRelayClientRegistry(ClientType localType) {
		super(localType);
	}

	@Override
	public short[] getRelayChain(short fromCode) {
		// FIXME: if fromCode is a TERMINUS and we are a EXIT_NODE, return { RELAY'S_CODE_TO_ENTRY_NODE, ENTRY'S_CODE_TO_CORRESPONDING_TERMINUS }
		// FIXME: if fromCode is a TERMINUS and we are a ENTRY_NODE, return { RELAY'S_CODE_TO_EXIT_NODE, EXIT'S_CODE_TO_CORRESPONDING_TERMINUS }
		throw new UnsupportedOperationException("Not yet implemented.");
	}

	@Override
	public void removeFromRelayTable(short... relayChain) {
		// FIXME: implement
		throw new UnsupportedOperationException("Not yet implemented");
	}
}

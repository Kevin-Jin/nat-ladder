package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.netimpl.ClientManagerNio;

import java.util.Collections;

public class NatLadderCentralRelay {
	public static void main(String[] args) {
		new ClientManagerNio(new CentralRelayClientRegistry()).listen(RemoteRouter.internalNodeFactory, 3425, Collections.<String, Object>emptyMap());
	}
}

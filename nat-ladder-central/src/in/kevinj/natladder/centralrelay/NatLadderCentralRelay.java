package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.netimpl.ClientManager;

public class NatLadderCentralRelay {
	public static void main(String[] args) {
		new ClientManager(new CentralRelayClientRegistry()).listen(RemoteRouter.internalNodeFactory, 3425);
	}
}

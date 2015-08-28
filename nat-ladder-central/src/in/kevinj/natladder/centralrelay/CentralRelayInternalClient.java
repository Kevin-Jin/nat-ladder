package in.kevinj.natladder.centralrelay;

import in.kevinj.natladder.common.model.RemoteRouter;

public class CentralRelayInternalClient extends RemoteRouter<CentralRelayClientRegistry> {
	public CentralRelayInternalClient(CentralRelayClientRegistry parentModel) {
		super(parentModel);
	}
}

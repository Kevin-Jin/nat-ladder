package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.model.SessionType;

public class ExitNodeInternalClient extends RemoteRouter<ExitNodeClientRegistry> {
	public ExitNodeInternalClient(ExitNodeClientRegistry parentModel, SessionType sessionType) {
		super(parentModel);
		setSessionType(sessionType);
	}
}

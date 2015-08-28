package in.kevinj.natladder.entrynode;

import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.model.SessionType;

public class EntryNodeInternalClient extends RemoteRouter<EntryNodeClientRegistry> {
	public EntryNodeInternalClient(EntryNodeClientRegistry parentModel, SessionType sessionType) {
		super(parentModel);
		setSessionType(sessionType);
	}
}

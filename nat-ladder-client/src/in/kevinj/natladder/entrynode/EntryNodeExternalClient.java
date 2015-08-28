package in.kevinj.natladder.entrynode;

import in.kevinj.natladder.common.model.RemoteNode;

public class EntryNodeExternalClient extends RemoteNode<EntryNodeClientRegistry> {
	public EntryNodeExternalClient(EntryNodeClientRegistry parentModel) {
		super(parentModel);
	}

	@Override
	protected void setRemoteCode(short nodeCode) {
		super.setRemoteCode(nodeCode);
	}
}

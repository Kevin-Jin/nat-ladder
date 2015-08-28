package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.RemoteNode;

public class ExitNodeExternalClient extends RemoteNode<ExitNodeClientRegistry> {
	public ExitNodeExternalClient(ExitNodeClientRegistry parentModel) {
		super(parentModel);
	}

	@Override
	protected void setRemoteCode(short nodeCode) {
		super.setRemoteCode(nodeCode);
	}
}

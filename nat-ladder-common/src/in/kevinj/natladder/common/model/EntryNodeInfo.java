package in.kevinj.natladder.common.model;

public class EntryNodeInfo {
	public final String exitNodeIdentifier;
	public final short connectedExitNode;

	public EntryNodeInfo(String exitNodeIdentifier, short connectedExitNode) {
		this.exitNodeIdentifier = exitNodeIdentifier;
		this.connectedExitNode = connectedExitNode;
	}
}

package in.kevinj.natladder.centralrelay;

public class EntryNodeInfo {
	public final String exitNodeIdentifier;
	public final short connectedExitNode;
	public boolean isLameDuck;

	public EntryNodeInfo(String exitNodeIdentifier, short connectedExitNode) {
		this.exitNodeIdentifier = exitNodeIdentifier;
		this.connectedExitNode = connectedExitNode;
	}
}

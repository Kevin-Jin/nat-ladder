package in.kevinj.natladder.common.model;

import java.util.HashSet;
import java.util.Set;

public class ExitNodeInfo {
	public final String identifier, password;
	public final int connectToPort;
	public final short nodeCode;
	public final Set<Short> connectedEntryNodes;

	public ExitNodeInfo(String identifier, String password, int connectToPort, short nodeCode) {
		this.identifier = identifier;
		this.password = password;
		this.connectToPort = connectToPort;
		this.nodeCode = nodeCode;

		connectedEntryNodes = new HashSet<Short>();
	}
}

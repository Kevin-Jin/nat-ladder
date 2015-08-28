package in.kevinj.natladder.common.netimpl;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.RemoteNode;

import java.util.Map;

public interface ClientManager<T extends LocalRouter<T>> {
	public void close(String reason, Throwable reasonExc);
	public void listen(RemoteNode.RemoteNodeFactory<T> clientMaker, String host, int port, Map<String, Object> properties);
	public void connect(RemoteNode.RemoteNodeFactory<T> clientMaker, String host, int port, Map<String, Object> properties);
	public boolean isShutdown();
}

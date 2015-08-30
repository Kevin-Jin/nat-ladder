package in.kevinj.natladder.common.model;

import in.kevinj.natladder.common.netimpl.ClientSession;
import in.kevinj.natladder.common.util.PacketParser;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class RemoteNode<T extends LocalRouter<T>> {
	protected static final Logger LOG = Logger.getLogger(RemoteNode.class.getName());

	public interface RemoteNodeFactory<T extends LocalRouter<T>> {
		public RemoteNode<T> make(T parentModel);
		public SessionType typeToMake();
	}

	private final T parentModel;

	private ClientSession<T> session;
	private short itsNodeCode;
	private boolean isNodeCodeSet;
	protected boolean closeQuietly;

	public RemoteNode(T parentModel) {
		assert parentModel != null;

		this.parentModel = parentModel;
	}

	public void setClientSession(ClientSession<T> session) {
		this.session = session;
		session.setPreClose(new Runnable() {
			@Override
			public void run() {
				if (!getLocalNode().getClientManager().isShutdown() && isRemoteCodeSet())
					dispose();
				if (getRemoteType() == ClientType.CENTRAL_RELAY)
					// lost connection to the central relay. just shut ourselves down.
					getLocalNode().getClientManager().close("Lost connection to " + ClientType.CENTRAL_RELAY, null);
			}
		});
	}

	public short[] notifyFoundCutExternal(short ourTerminus) {
		throw new UnsupportedOperationException("RemoteNode does not support sending notifications about external clients");
	}

	public void notifyFoundCutInternal(short otherNode) {
		throw new UnsupportedOperationException("RemoteNode does not support sending notifications to external clients");
	}

	public ClientSession<T> getClientSession() {
		return session;
	}

	protected void setRemoteCode(short nodeCode) {
		if (isNodeCodeSet)
			throw new IllegalStateException("Invalid remote node code " + nodeCode + " (to replace " + getRemoteCode() + ")");

		itsNodeCode = nodeCode;
		isNodeCodeSet = true;
	}

	public boolean isRemoteCodeSet() {
		return isNodeCodeSet;
	}

	public short getRemoteCode() {
		if (!isNodeCodeSet)
			throw new IllegalStateException("Invalid remote node code null");

		return itsNodeCode;
	}

	public T getLocalNode() {
		return parentModel;
	}

	public SessionType getSessionType() {
		return SessionType.TERMINUS;
	}

	protected ClientType getRemoteType() {
		return null;
	}

	public static String getRemoteTypeString(SessionType sessionType, ClientType remoteType) {
		if (remoteType == null)
			return sessionType.toString();
		else
			return remoteType.toString();
	}

	public String getRemoteTypeString() {
		return getRemoteTypeString(getSessionType(), getRemoteType());
	}

	public boolean forwardRaw() {
		return true;
	}

	public void setThisMessageDest(short nodeCode) {
		throw new UnsupportedOperationException("RemoteNode does not require message dest code set");
	}

	public boolean isThisMessageForUs() {
		return false;
	}

	public abstract void onConnected(Map<String, Object> properties);

	public RemoteNode<T> getNextNode() {
		return getLocalNode().getCentralRelayLink();
	}

	public void processControlPacket(PacketParser packet) {
		throw new UnsupportedOperationException("RemoteNode does not accept control packets");
	}

	public void foundNextNodeCut() {
		// if we're a TERMINUS, then next node must be central relay.
		// lost connection to the central relay. just shut ourselves down.
		getLocalNode().getClientManager().close("Lost connection to " + ClientType.CENTRAL_RELAY, null);
	}

	protected void dispose() {
		// notify those who care that we are shutting ourself down
		LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} lost", new Object[] { getRemoteTypeString(), getRemoteCode(), getClientSession().getAddress() });

		// disconnecting from terminus. send message through central relay to notify opposite end.
		RemoteNode<T> centralRelay = getNextNode();
		if (!closeQuietly && centralRelay != null)
			centralRelay.notifyFoundCutExternal(getRemoteCode());
		// deregister TERMINUS
		getLocalNode().deregisterNode(this);
	}

	public void quietClose(String reason) {
		closeQuietly = true;
		getClientSession().close(reason);
	}
}

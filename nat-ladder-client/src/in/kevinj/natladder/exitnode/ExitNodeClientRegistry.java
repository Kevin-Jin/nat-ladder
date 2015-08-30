package in.kevinj.natladder.exitnode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.model.SessionType;

public class ExitNodeClientRegistry extends LocalRouter<ExitNodeClientRegistry> {
	private final Map<String, Object> properties;

	public ExitNodeClientRegistry(ClientType localType, String terminusHost, int terminusPort) {
		super(localType);
		properties = new HashMap<String, Object>();

		setProperty("terminusHost", terminusHost);
		setProperty("terminusPort", Integer.valueOf(terminusPort));
	}

	@Override
	public RemoteNodeFactory<ExitNodeClientRegistry> internalNodeFactory() {
		return new RemoteNodeFactory<ExitNodeClientRegistry>() {
			@Override
			public ExitNodeToCentralRelay make(ExitNodeClientRegistry parentModel) {
				return new ExitNodeToCentralRelay(parentModel, SessionType.UPWARDS_RELAY);
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.UPWARDS_RELAY;
			}
		};
	}

	@Override
	public RemoteNodeFactory<ExitNodeClientRegistry> externalNodeFactory() {
		return new RemoteNodeFactory<ExitNodeClientRegistry>() {
			@Override
			public ExitNodeToTerminus make(ExitNodeClientRegistry parentModel) {
				ExitNodeToTerminus node = new ExitNodeToTerminus(parentModel);
				node.setRemoteCode(parentModel.registerNode(node));
				return node;
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.TERMINUS;
			}
		};
	}

	// TODO: not type-safe and a code smell. replace functionality with polymorphism somehow.
	private boolean setProperty(String prop, Object value) {
		return properties.put(prop, value) != null;
	}

	@SuppressWarnings("unchecked")
	private void extendProperty(String prop, Object... values) {
		Object existing = properties.get(prop);
		if (existing == null) {
			existing = new ArrayList<Object>();
			properties.put(prop, existing);
		} else if (!(existing instanceof Collection)) {
			throw new IllegalStateException(prop + " is not a list property.");
		}

		((Collection<Object>) existing).addAll(Arrays.asList(values));
	}

	private Object getProperty(String prop) {
		return properties.get(prop);
	}

	private Object removeProperty(String prop) {
		return properties.remove(prop);
	}

	public void linkAttempt(short... entryNodeRelayChain) {
		extendProperty("INPROGRESS_" + entryNodeRelayChain[0], Short.valueOf(entryNodeRelayChain[1]));
	}

	public boolean linkFailed(ExitNodeToCentralRelay internalLink, short... entryNodeRelayChain) {
		boolean found = false;

		// first try to stop the pending connection attempt.
		Collection<?> ourTermini = (Collection<?>) getProperty("INPROGRESS_" + entryNodeRelayChain[0]);
		if (ourTermini != null && ourTermini.contains(Short.valueOf(entryNodeRelayChain[1]))) {
			setProperty("IGNORE_" + entryNodeRelayChain[0] + "_" + entryNodeRelayChain[1], Boolean.TRUE);
			found = true;
		}

		// then deregister in case the connection succeeded after entry node sent the notification.
		ourTermini = (Collection<?>) getProperty("REVERSE_" + entryNodeRelayChain[0]);
		if (ourTermini != null) {
			for (Iterator<?> iter = ourTermini.iterator(); iter.hasNext() && !found; ) {
				short ourTerminus = ((Short) iter.next()).shortValue();
				short[] relayChain = (short[]) getProperty("RELAYCHAIN_" + ourTerminus);
				if (relayChain[1] == entryNodeRelayChain[1]) {
					// found the node we're looking for. it was connected.
					iter.remove(); // remove from REVERSE_
					removeProperty("RELAYCHAIN_" + ourTerminus);
					// undo our first step since it's no longer needed.
					removeProperty("IGNORE_" + entryNodeRelayChain[0] + "_" + entryNodeRelayChain[1]);
					internalLink.getNextNode(ourTerminus).quietClose("Lost connection on source node");
					found = true;
				}
			}
		}

		return found;
	}

	public boolean linkEstablished(short ourTerminus, short... entryNodeRelayChain) {
		// unmark our connection as "in progress"
		Collection<?> ourTermini = (Collection<?>) getProperty("INPROGRESS_" + entryNodeRelayChain[0]);
		boolean removed = (ourTermini != null && ourTermini.remove(Short.valueOf(entryNodeRelayChain[1])));

		Boolean ignore = (Boolean) removeProperty("IGNORE_" + entryNodeRelayChain[0] + "_" + entryNodeRelayChain[1]);
		if (ignore == null || !ignore.booleanValue()) {			// should only be false if entry node disconnected while this connection was pending.
			// in that case, ignore is true and this execution path should not have been followed.
			if (!removed)
				throw new IllegalStateException("Completed a non-existent connection (remote node code: " + entryNodeRelayChain[0] + "," + entryNodeRelayChain[1] + ")");

			// set our relay chain and relay chain reverse mapping
			setProperty("RELAYCHAIN_" + ourTerminus, entryNodeRelayChain);
			extendProperty("REVERSE_" + entryNodeRelayChain[0], ourTerminus);
			return true;
		}
		return false;
	}

	// terminus link on entry node disconnected
	public void linkLostTheirEnd(short ourTerminus, short... entryNodeRelayChain) {
		Collection<?> ourTermini = (Collection<?>) getProperty("REVERSE_" + entryNodeRelayChain[0]);
		if (ourTermini == null || !ourTermini.remove(Short.valueOf(ourTerminus)))
			throw new IllegalStateException("Inconsistent state in RELAYCHAIN_ or REVERSE_ (node code: " + ourTerminus + ")");
	}

	// terminus link on exit node disconnected
	public void linkLostOurEnd(short ourTerminus, short... entryNodeRelayChain) {
		if (entryNodeRelayChain != null) {
			Collection<?> ourTermini = (Collection<?>) getProperty("REVERSE_" + entryNodeRelayChain[0]);
			if (ourTermini == null || !ourTermini.remove(Short.valueOf(ourTerminus)))
				throw new IllegalStateException("Inconsistent state in RELAYCHAIN_ or REVERSE_ (node code: " + ourTerminus + ")");
		} else {
			throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");
		}
	}

	// entry node disconnected
	public void linksLost(ExitNodeToCentralRelay internalLink, short entryNode) {
		// cut terminus connections that relay through the provided entry node
		Collection<?> ourTermini = (Collection<?>) removeProperty("REVERSE_" + entryNode);
		RemoteNode<ExitNodeClientRegistry> externalLink;
		if (ourTermini != null)
			// at least one pipe exists through the entry node
			for (Object ourTerminus : ourTermini)
				// we're being notified by CENTRAL_RELAY. no need to echo back the cut notification to CENTRAL_RELAY
				if ((externalLink = internalLink.getNextNode(((Short) ourTerminus).shortValue())) != null)
					externalLink.quietClose("Lost connection to entry node");
				else
					throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");

		// cut connections in progress
		ourTermini = (Collection<?>) removeProperty("INPROGRESS_" + entryNode);
		if (ourTermini != null)
			for (Object ourTerminus : ourTermini)
				setProperty("IGNORE_" + entryNode + "_" + ourTerminus, Boolean.TRUE);
	}

	public String getTerminusHost() {
		return (String) getProperty("terminusHost");
	}

	public int getTerminusPort() {
		return ((Integer) getProperty("terminusPort")).intValue();
	}

	@Override
	public int getIntermediateHops() {
		// TODO: should receive this in ACCEPTED packet. should be the number of hops to TERMINUS on other side,
		// i.e. 2 because we must go through CENTRAL_RELAY and ENTRY_NODE
		return 2;
	}

	@Override
	public short[] getRelayChain(short nodeCode) {
		return (short[]) getProperty("RELAYCHAIN_" + nodeCode);
	}

	@Override
	public short[] removeRelayChain(short nodeCode) {
		return (short[]) removeProperty("RELAYCHAIN_" + nodeCode);
	}

	@Override
	protected short registerNode(RemoteNode<ExitNodeClientRegistry> node) {
		short nodeCode;
		switch (node.getSessionType()) {
			case UPWARDS_RELAY:
				assert node.getRemoteCode() == ClientType.CENTRAL_RELAY_NODE_CODE : node.getRemoteCode();
				nodeCode = registerUpstream(node);
				break;
			case TERMINUS:
				assert !node.isRemoteCodeSet();
				nodeCode = registerDownstream(node);
				break;
			default:
				throw new IllegalStateException("Invalid session type " + node.getSessionType());
		}
		return nodeCode;
	}

	@Override
	protected RemoteNode<ExitNodeClientRegistry> deregisterNode(SessionType sessionType, short nodeCode) {
		switch (sessionType) {
			case UPWARDS_RELAY:
				assert nodeCode == ClientType.CENTRAL_RELAY_NODE_CODE;
				return deregisterUpstream(nodeCode);
			case TERMINUS:
				assert nodeCode < 0;
				return deregisterDownstream(nodeCode);
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}

	@Override
	public ClientType getRemoteType(SessionType link) {
		switch (link) {
			case UPWARDS_RELAY:
				return ClientType.CENTRAL_RELAY;
			case TERMINUS:
				return null;
			default:
				throw new IllegalStateException("Invalid session type " + link + " for " + getLocalType());
		}
	}

	@Override
	public RemoteNode<ExitNodeClientRegistry> getCentralRelayLink() {
		return getUpstream(ClientType.CENTRAL_RELAY_NODE_CODE);
	}

	@Override
	public void onConnectFailed(SessionType sessionType, Map<String, Object> properties, Throwable ex) {
		switch (sessionType) {
			case UPWARDS_RELAY:
				super.onConnectFailed(sessionType, properties, ex);
				break;
			case TERMINUS: {
				LOG.log(Level.WARNING, "Failed to establish connection with " + RemoteNode.getRemoteTypeString(sessionType, getRemoteType(sessionType)), ex);
				short[] entryNodeRelayChain = (short[]) properties.get("entryNodeRelayChain");
				getCentralRelayLink().getClientSession().packetBuilder(Byte.SIZE / 8 + Short.SIZE / 8, entryNodeRelayChain[0], LocalRouter.CONTROL_CODE)
					.writeByte(PacketHeaders.PIPE_FAIL)
					.writeShort(entryNodeRelayChain[1])
				.send();
				break;
			}
			default:
				throw new IllegalStateException("Invalid session type " + sessionType);
		}
	}
}

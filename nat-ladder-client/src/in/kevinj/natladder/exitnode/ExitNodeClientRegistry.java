package in.kevinj.natladder.exitnode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
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

	public ExitNodeClientRegistry(ClientType localType) {
		super(localType);
		properties = new HashMap<String, Object>();
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
	public boolean setProperty(String prop, Object value) {
		return properties.put(prop, value) != null;
	}

	@SuppressWarnings("unchecked")
	public void extendProperty(String prop, Object... values) {
		Object existing = properties.get(prop);
		if (existing == null) {
			existing = new ArrayList<Object>();
			properties.put(prop, existing);
		} else if (!(existing instanceof Collection)) {
			throw new IllegalStateException(prop + " is not a list property.");
		}

		((Collection<Object>) existing).addAll(Arrays.asList(values));
	}

	public Object getProperty(String prop) {
		return properties.get(prop);
	}

	public Object removeProperty(String prop) {
		return properties.remove(prop);
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

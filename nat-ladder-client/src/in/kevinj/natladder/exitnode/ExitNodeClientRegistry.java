package in.kevinj.natladder.exitnode;

import java.util.Map;
import java.util.logging.Level;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteNode.RemoteNodeFactory;
import in.kevinj.natladder.common.model.SessionType;

public class ExitNodeClientRegistry extends LocalRouter<ExitNodeClientRegistry> {
	public ExitNodeClientRegistry(ClientType localType) {
		super(localType);
	}

	@Override
	public RemoteNodeFactory<ExitNodeClientRegistry> internalNodeFactory() {
		return new RemoteNodeFactory<ExitNodeClientRegistry>() {
			@Override
			public ExitNodeInternalClient make(ExitNodeClientRegistry parentModel) {
				return new ExitNodeInternalClient(parentModel, SessionType.UPWARDS_RELAY);
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
			public ExitNodeExternalClient make(ExitNodeClientRegistry parentModel) {
				ExitNodeExternalClient node = new ExitNodeExternalClient(parentModel);
				node.setRemoteCode(parentModel.registerNode(node));
				return node;
			}

			@Override
			public SessionType typeToMake() {
				return SessionType.TERMINUS;
			}
		};
	}

	@Override
	public short registerNode(RemoteNode<ExitNodeClientRegistry> node) {
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
	public RemoteNode<ExitNodeClientRegistry> deregisterNode(SessionType sessionType, short nodeCode) {
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

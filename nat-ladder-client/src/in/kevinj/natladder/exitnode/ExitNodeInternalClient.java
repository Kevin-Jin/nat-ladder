package in.kevinj.natladder.exitnode;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.PacketHeaders;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.RemoteRouter;
import in.kevinj.natladder.common.model.SessionType;
import in.kevinj.natladder.common.util.PacketParser;
import in.kevinj.natladder.common.util.Pair;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;

public class ExitNodeInternalClient extends RemoteRouter<ExitNodeClientRegistry> {
	public ExitNodeInternalClient(ExitNodeClientRegistry parentModel, SessionType sessionType) {
		super(parentModel);
		setSessionType(sessionType);
	}

	@Override
	public void onConnected(Map<String, Object> properties) {
		assert getSessionType() != null : getSessionType();

		// notify central relay that we are an EXIT_NODE
		getClientSession().packetBuilder(LocalRouter.CONTROL_CODE)
			.writeByte(PacketHeaders.IDENTIFY)
			// relative to central relay, exit nodes are downstream
			.writeByte(getSessionType().invert().byteValue())
			.writeString((String) properties.get("identifier"))
			.writeString((String) properties.get("password"))
			.writeInt(((Integer) properties.get("connectToPort")).intValue())
		.send();
	}

	@Override
	public short[] notifyFoundCutExternal(short ourTerminus) {
		short[] relayChain = super.notifyFoundCutExternal(ourTerminus);
		if (relayChain != null) {
			Collection<?> ourTermini = (Collection<?>) getLocalNode().getProperty("REVERSE_" + relayChain[0]);
			if (ourTermini == null || !ourTermini.remove(Short.valueOf(ourTerminus)))
				throw new IllegalStateException("Inconsistent state in RELAYCHAIN_ or REVERSE_ (node code: " + ourTerminus + ")");
		} else {
			throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");
		}

		return relayChain;
	}

	@Override
	protected void processIdentify(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processRejected(PacketParser packet) {
		byte rejectedReason = packet.readByte();
		switch (rejectedReason) {
			case PacketHeaders.REJECTED_REASON_ID_IN_USE:
				// FIXME: implement command line interaction telling user to re-enter
				// identifier and password to register with
				LOG.log(Level.INFO, "Connection with {0} ({1}) at {2} rejected: identifier in use", new Object[] { getRemoteTypeString(), null, getClientSession().getAddress() });
				break;
			default:
				throw new IllegalStateException("Invalid rejected reason " + rejectedReason);
		}
	}

	@Override
	protected Pair<Short, short[]> processFoundCut(PacketParser packet) {
		byte foundCutType = packet.readByte();
		switch (foundCutType) {
			case PacketHeaders.FOUND_CUT_TERMINUS: {
				Pair<Short, short[]> info = super.processFoundCut(packet);
				short ourTerminus = info.left.shortValue();
				short[] relayChain = info.right;

				Collection<?> ourTermini = (Collection<?>) getLocalNode().getProperty("REVERSE_" + relayChain[0]);
				if (ourTermini == null || !ourTermini.remove(Short.valueOf(ourTerminus)))
					throw new IllegalStateException("Inconsistent state in RELAYCHAIN_ or REVERSE_ (node code: " + ourTerminus + ")");
				return info;
			}
			case PacketHeaders.FOUND_CUT_NODE: {
				short otherNode = packet.readShort();

				// cut terminus connections that relay through the provided entry node
				Collection<?> ourTermini = (Collection<?>) getLocalNode().removeProperty("REVERSE_" + otherNode);
				RemoteNode<ExitNodeClientRegistry> node;
				if (ourTermini != null)
					// at least one pipe exists through the entry node
					for (Object ourTerminus : ourTermini)
						// we're being notified by CENTRAL_RELAY. no need to echo back the cut notification to CENTRAL_RELAY
						if ((node = getNextNode(((Short) ourTerminus).shortValue())) != null)
							node.quietClose("Lost connection to entry node");
						else
							throw new IllegalStateException("Cut a non-existent connection (node code: " + ourTerminus + ")");

				// cut connections in progress
				ourTermini = (Collection<?>) getLocalNode().removeProperty("INPROGRESS_" + otherNode);
				if (ourTermini != null)
					for (Object ourTerminus : ourTermini)
						getLocalNode().setProperty("IGNORE_" + otherNode + "_" + ourTerminus, Boolean.TRUE);
				return null;
			}
			default:
				throw new IllegalStateException("Invalid found cut type " + foundCutType);
		}
	}

	@Override
	protected void processMakePipe(PacketParser packet) {
		short entryNodeCode = packet.readShort();
		short terminusCode = packet.readShort();
		getLocalNode().extendProperty("INPROGRESS_" + entryNodeCode, Short.valueOf(terminusCode));
		getLocalNode().getClientManager().connect(getLocalNode().externalNodeFactory(),
			(String) getLocalNode().getProperty("terminusHost"),
			((Integer) getLocalNode().getProperty("terminusPort")).intValue(),
			Collections.<String, Object>singletonMap("entryNodeRelayChain", new short[] { entryNodeCode, terminusCode })
		);
	}

	@Override
	protected void processPipeMade(PacketParser packet) {
		throw new UnsupportedOperationException(getLocalNode().getLocalType() + " does not support this packet");
	}

	@Override
	protected void processPipeFail(PacketParser packet) {
		short entryNodeCode = packet.readShort();
		short theirTerminus = packet.readShort();

		// first try to stop the pending connection attempt.
		boolean found = false;
		Collection<?> ourTermini = (Collection<?>) getLocalNode().getProperty("INPROGRESS_" + entryNodeCode);
		if (ourTermini != null && ourTermini.contains(Short.valueOf(theirTerminus))) {
			getLocalNode().setProperty("IGNORE_" + entryNodeCode + "_" + theirTerminus, Boolean.TRUE);
			found = true;
		}

		// then deregister in case the connection succeeded after entry node sent the notification.
		ourTermini = (Collection<?>) getLocalNode().getProperty("REVERSE_" + entryNodeCode);
		if (ourTermini != null) {
			for (Iterator<?> iter = ourTermini.iterator(); iter.hasNext() && !found; ) {
				short ourTerminus = ((Short) iter.next()).shortValue();
				short[] relayChain = (short[]) getLocalNode().getProperty("RELAYCHAIN_" + ourTerminus);
				if (relayChain[1] == theirTerminus) {
					// found the node we're looking for. it was connected.
					iter.remove(); // remove from REVERSE_
					getLocalNode().removeProperty("RELAYCHAIN_" + ourTerminus);
					// undo our first step since it's no longer needed.
					getLocalNode().removeProperty("IGNORE_" + entryNodeCode + "_" + theirTerminus);
					getNextNode(ourTerminus).quietClose("Lost connection on source node");
					found = true;
				}
			}
		}

		if (!found)
			throw new IllegalStateException("Cut a non-existent connection (remote node code: " + entryNodeCode + "," + theirTerminus + ")");
	}
}

package in.kevinj.natladder.common.netimpl;

import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.RemoteNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientManager {
	private static final Logger LOG = Logger.getLogger(ClientManager.class.getName());

	private final ExecutorService eventLoopThreadPool = Executors.newSingleThreadExecutor(new ThreadFactory() {
		private final ThreadGroup group;

		{
			SecurityManager s = System.getSecurityManager();
			group = (s != null)? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(group, r, "event-loop-thread", 0);
			if (t.isDaemon())
				t.setDaemon(false);
			if (t.getPriority() != Thread.NORM_PRIORITY)
				t.setPriority(Thread.NORM_PRIORITY);
			return t;
		}
	});

	// TODO: round robin new connections to a set of selectors running
	// on different threads for handling reading and writing in parallel
	// while still keeping allowing some ClientSession code to not be thread-safe.
	private class EventLoopTask implements Runnable {
		private RemoteNode.RemoteNodeFactory acceptorClientMaker, connectorClientMaker;
		private final Map<SelectionKey, ServerSocketChannel> listeners;
		private final Map<SelectionKey, SocketChannel> pendingConnections;

		public EventLoopTask() {
			listeners = new HashMap<SelectionKey, ServerSocketChannel>();
			pendingConnections = new HashMap<SelectionKey, SocketChannel>();
		}

		public void addConnector(RemoteNode.RemoteNodeFactory clientMaker, SocketChannel socket) throws ClosedChannelException {
			connectorClientMaker = clientMaker;
			pendingConnections.put(socket.register(selector, SelectionKey.OP_CONNECT), socket);
		}

		public void addAcceptor(RemoteNode.RemoteNodeFactory clientMaker, ServerSocketChannel socket) throws ClosedChannelException {
			acceptorClientMaker = clientMaker;
			listeners.put(socket.register(selector, SelectionKey.OP_ACCEPT), socket);
		}

		private void registerNewClient(SocketChannel client, final Map<SelectionKey, ClientSession> connected, RemoteNode.RemoteNodeFactory clientMaker) {
			try {
				client.socket().setTcpNoDelay(true);
				client.configureBlocking(false);
				final SelectionKey acceptedKey = client.register(selector, SelectionKey.OP_READ);
				RemoteNode clientState = clientMaker.make(model);
				ClientSession session = new ClientSession(clientState, client, acceptedKey, new Runnable() {
					@Override
					public void run() {
						connected.remove(acceptedKey);
					}
				});
				clientState.setClientSession(session);
				connected.put(acceptedKey, session);

				clientState.sendInitPacket();
			} catch (IOException ex) {
				//does an IOException in accept or connect always mean an invalid server channel?
				close(ex.getMessage(), ex);
			}
		}

		private void readForClient(SocketChannel client, ClientSession session) {
			try {
				int read = client.read(session.readBuffer());
				while (session.readMessage(read)) {
					// possibly just read only part of the packet:
					// try more non-blocking reads in case we have more
					read = client.read(session.readBuffer());
				}
			} catch (IOException ex) {
				//does an IOException in read always mean an invalid channel?
				session.close(ex.getMessage());
			}
		}

		private void writeForClient(SocketChannel client, ClientSession session, SelectionKey key) {
			if (session.tryFlushSendQueue() == 1)
				key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);					
		}

		private void cleanupAll(Map<SelectionKey, ClientSession> connected) throws IOException {
			for (Iterator<Map.Entry<SelectionKey, ClientSession>> iter = connected.entrySet().iterator(); iter.hasNext(); ) {
				Map.Entry<SelectionKey, ClientSession> item = iter.next();
				iter.remove();
				item.getKey().cancel();
				item.getValue().close("Network event selector shutdown");
			}
			for (Iterator<Map.Entry<SelectionKey, SocketChannel>> iter = pendingConnections.entrySet().iterator(); iter.hasNext(); ) {
				Map.Entry<SelectionKey, SocketChannel> item = iter.next();
				iter.remove();
				item.getKey().cancel();
				item.getValue().close();
			}
			for (Iterator<Map.Entry<SelectionKey, ServerSocketChannel>> iter = listeners.entrySet().iterator(); iter.hasNext(); ) {
				Map.Entry<SelectionKey, ServerSocketChannel> item = iter.next();
				iter.remove();
				item.getKey().cancel();
				item.getValue().close();
			}
		}

		@Override
		public void run() {
			try {
				// allows type safe, unlike SelectionKey.attach()
				Map<SelectionKey, ClientSession> connected = new ConcurrentHashMap<SelectionKey, ClientSession>();
				while (selector.isOpen()) {
					selector.select();
					Set<SelectionKey> keys = selector.selectedKeys();

					for (Iterator<SelectionKey> keyIter = keys.iterator(); keyIter.hasNext(); ) {
						SelectionKey key = keyIter.next();
						keyIter.remove();

						ServerSocketChannel listener;
						SocketChannel client;
						try {
							if (key.isValid() && key.isAcceptable())
								if ((listener = listeners.get(key)) != null)
									registerNewClient(listener.accept(), connected, acceptorClientMaker);
								else
									close("Network event selector was manipulated outside of connect() and listen()", null);
							if (key.isValid() && key.isConnectable() && (!(client = (SocketChannel) key.channel()).isConnectionPending() || client.finishConnect()))
								if (pendingConnections.remove(key) == client)
									registerNewClient(client, connected, connectorClientMaker);
								else
									close("Network event selector was manipulated outside of connect() and listen()", null);
							if (key.isValid() && key.isReadable())
								readForClient(client = (SocketChannel) key.channel(), connected.get(key));
							if (key.isValid() && key.isWritable())
								writeForClient(client = (SocketChannel) key.channel(), connected.get(key), key);
						} catch (CancelledKeyException e) {
							// don't worry about it - session is already closed
						}
					}
				}
				cleanupAll(connected);
			} catch (IOException ex) {
				close(ex.getMessage(), ex);
			}
		}
	}

	private final LocalRouter model;

	private final AtomicBoolean closeEventsTriggered;
	private final EventLoopTask eventLoop;
	private Selector selector;

	public ClientManager(LocalRouter thisState) {
		model = thisState;
		closeEventsTriggered = new AtomicBoolean(false);
		eventLoop = new EventLoopTask();
	}

	public void close(String reason, Throwable reasonExc) {
		if (closeEventsTriggered.compareAndSet(false, true)) {
			try {
				selector.close();
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Error while closing network event selector", ex);
			}
			if (reasonExc == null)
				LOG.log(Level.INFO, "Network event selector closed ({1})", reason);
			else
				LOG.log(Level.INFO, "Network event selector closed (" + reason + ")", reasonExc);
			eventLoopThreadPool.shutdown();
		}
	}

	// FIXME: internalNodeFactory for central relay, externalNodeFactory for entry node
	public void listen(RemoteNode.RemoteNodeFactory clientMaker, int port) {
		SocketAddress address = new InetSocketAddress(port);
		try {
			ServerSocketChannel listener = ServerSocketChannel.open();
			listener.socket().bind(address);
			listener.configureBlocking(false);

			if (selector != null) {
				selector = Selector.open();
				eventLoopThreadPool.submit(eventLoop);
			}
			eventLoop.addAcceptor(clientMaker, listener);
			LOG.log(Level.INFO, "Listening on {0}", address);
		} catch (IOException ex) {
			LOG.log(Level.SEVERE, "Could not bind on " + address, ex);
		}
	}

	// FIXME: externalNodeFactory for exit node -> terminus,
	// downwardsRelayFactory for entry node -> central relay, upwardsRelayFactory for exit node -> central relay
	public void connect(RemoteNode.RemoteNodeFactory clientMaker, String host, int port) {
		SocketAddress address = new InetSocketAddress(host, port);
		try {
			SocketChannel speaker = SocketChannel.open();
			speaker.configureBlocking(false);
			speaker.connect(address);

			if (selector != null) {
				selector = Selector.open();
				eventLoopThreadPool.submit(eventLoop);
			}
			eventLoop.addConnector(clientMaker, speaker);
			LOG.log(Level.INFO, "Connecting to {0}", address);
		} catch (IOException ex) {
			LOG.log(Level.SEVERE, "Could not connect to " + address, ex);
		}
	}
}

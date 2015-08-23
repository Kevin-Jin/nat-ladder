package in.kevinj.natladder.common.netimpl;

import in.kevinj.natladder.common.model.ClientType;
import in.kevinj.natladder.common.model.LocalRouter;
import in.kevinj.natladder.common.model.RemoteNode;
import in.kevinj.natladder.common.model.SessionType;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientManagerNio implements ClientManager {
	private static final Logger LOG = Logger.getLogger(ClientManagerNio.class.getName());

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
		private Selector selector;
		private final Map<SelectionKey, ServerSocketChannel> listeners;
		private final Map<SelectionKey, SocketChannel> pendingConnections;
		private final Map<SelectionKey, RemoteNode.RemoteNodeFactory> clientMakers;
		private final Map<SelectionKey, Map<String, Object>> newConnectionProps;
		private final List<Runnable> runInEventLoop;

		public EventLoopTask() {
			listeners = new HashMap<SelectionKey, ServerSocketChannel>();
			pendingConnections = new HashMap<SelectionKey, SocketChannel>();
			newConnectionProps = new HashMap<SelectionKey, Map<String, Object>>();
			clientMakers = new HashMap<SelectionKey, RemoteNode.RemoteNodeFactory>();
			runInEventLoop = new ArrayList<Runnable>();
		}

		// see http://stackoverflow.com/q/3189153/444402. to reduce the headache,
		// just wakeup the selector thread when we want to register channels
		private void invokeLater(Runnable r) {
			synchronized (runInEventLoop) {
				runInEventLoop.add(r);
				if (selector != null)
					selector.wakeup();
			}
		}

		public void addConnector(final SocketAddress address, final RemoteNode.RemoteNodeFactory clientMaker, final SocketChannel socket, final Map<String, Object> properties) {
			invokeLater(new Runnable() {
				@Override
				public void run() {
					SelectionKey key;
					try {
						key = socket.register(selector, SelectionKey.OP_CONNECT);
						clientMakers.put(key, clientMaker);
						pendingConnections.put(key, socket);
						newConnectionProps.put(key, properties != null ? properties : Collections.<String, Object>emptyMap());
						LOG.log(Level.INFO, "Connecting to {0}", address);
					} catch (ClosedChannelException ex) {
						close("Could not connect to " + address, ex);
					}
				}
			});
		}

		public void addAcceptor(final SocketAddress address, final RemoteNode.RemoteNodeFactory clientMaker, final ServerSocketChannel socket, final Map<String, Object> properties) {
			invokeLater(new Runnable() {
				@Override
				public void run() {
					try {
						SelectionKey key = socket.register(selector, SelectionKey.OP_ACCEPT);
						clientMakers.put(key, clientMaker);
						listeners.put(key, socket);
						newConnectionProps.put(key, properties != null ? properties : Collections.<String, Object>emptyMap());
						LOG.log(Level.INFO, "Listening on {0}", address);				
					} catch (ClosedChannelException ex) {
						close("Could not bind on " + address, ex);
					}
				}
			});
		}

		public void closeSelector() {
			invokeLater(new Runnable() {
				@Override
				public void run() {
					try {
						selector.close();
					} catch (IOException ex) {
						LOG.log(Level.WARNING, "Error while closing network event selector", ex);
					}
				}
			});
		}

		private ClientSessionNio registerNewClient(SocketChannel client, final Map<SelectionKey, ClientSessionNio> connected, RemoteNode.RemoteNodeFactory clientMaker, Map<String, Object> properties) {
			try {
				client.socket().setTcpNoDelay(true);
				client.configureBlocking(false);
				final SelectionKey acceptedKey = client.register(selector, SelectionKey.OP_READ);
				RemoteNode clientState = clientMaker.make(model);
				ClientSessionNio session = new ClientSessionNio(clientState, client, acceptedKey, new Runnable() {
					@Override
					public void run() {
						connected.remove(acceptedKey);
					}
				});
				clientState.setClientSession(session);
				connected.put(acceptedKey, session);

				clientState.onConnected(properties);
				return session;
			} catch (IOException ex) {
				//does an IOException in accept or connect always mean an invalid server channel?
				close(ex.getMessage(), ex);
				return null;
			}
		}

		private void readForClient(SocketChannel client, ClientSessionNio session) {
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

		private void writeForClient(SocketChannel client, ClientSessionNio session, SelectionKey key) {
			if (session.tryFlushSendQueue() == 1)
				key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);					
		}

		private void cleanupAll(Map<SelectionKey, ClientSessionNio> connected) {
			for (Iterator<Map.Entry<SelectionKey, ClientSessionNio>> iter = connected.entrySet().iterator(); iter.hasNext(); ) {
				Map.Entry<SelectionKey, ClientSessionNio> item = iter.next();
				iter.remove();
				item.getKey().cancel();
				item.getValue().close("Network event selector shutdown");
			}
			for (Iterator<Map.Entry<SelectionKey, SocketChannel>> iter = pendingConnections.entrySet().iterator(); iter.hasNext(); ) {
				Map.Entry<SelectionKey, SocketChannel> item = iter.next();
				iter.remove();
				item.getKey().cancel();
				try {
					item.getValue().close();
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Error while terminating pending connection at " + item.getValue().socket().getRemoteSocketAddress(), ex);
				}
			}
			for (Iterator<Map.Entry<SelectionKey, ServerSocketChannel>> iter = listeners.entrySet().iterator(); iter.hasNext(); ) {
				Map.Entry<SelectionKey, ServerSocketChannel> item = iter.next();
				iter.remove();
				item.getKey().cancel();
				try {
					item.getValue().close();
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Error while terminating listener at " + item.getValue().socket().getLocalSocketAddress(), ex);
				}
			}
		}

		@Override
		public void run() {
			// allows type safety, unlike SelectionKey.attach()
			Map<SelectionKey, ClientSessionNio> connected = new ConcurrentHashMap<SelectionKey, ClientSessionNio>();
			try {
				selector = Selector.open();
				// in case !runInEventLoop.isEmpty()
				selector.wakeup();
				while (selector.isOpen()) {
					selector.select();
					Set<SelectionKey> keys = selector.selectedKeys();
					synchronized (runInEventLoop) {
						for (Iterator<Runnable> iter = runInEventLoop.iterator(); iter.hasNext(); ) {
							iter.next().run();
							iter.remove();
						}
					}

					for (Iterator<SelectionKey> keyIter = keys.iterator(); keyIter.hasNext(); ) {
						SelectionKey key = keyIter.next();
						keyIter.remove();

						ServerSocketChannel listener;
						SocketChannel client = null;
						Map<String, Object> newConnProps;
						ClientSessionNio session = null;
						try {
							if (key.isValid() && key.isAcceptable())
								if ((listener = listeners.get(key)) != null && (newConnProps = newConnectionProps.get(key)) != null)
									session = registerNewClient(client = listener.accept(), connected, clientMakers.get(key), newConnProps);
								else
									close("Network event selector was manipulated outside of connect() and listen()", null);
							if (key.isValid() && key.isConnectable() && (!(client = (SocketChannel) key.channel()).isConnectionPending() || client.finishConnect()))
								if (pendingConnections.remove(key) == client && (newConnProps = newConnectionProps.get(key)) != null)
									session = registerNewClient(client, connected, clientMakers.remove(key), newConnProps);
								else
									close("Network event selector was manipulated outside of connect() and listen()", null);
							if (key.isValid() && key.isReadable())
								if ((session = connected.get(key)) != null)
									readForClient(client = (SocketChannel) key.channel(), session);
								else
									close("Network event selector was manipulated outside of connect() and listen()", null);
							if (key.isValid() && key.isWritable())
								if ((session = connected.get(key)) != null)
									writeForClient(client = (SocketChannel) key.channel(), session, key);
								else
									close("Network event selector was manipulated outside of connect() and listen()", null);
						} catch (CancelledKeyException e) {
							// don't worry about it - session is already closed
						} catch (ConnectException ex) {
							pendingConnections.remove(key);
							SessionType sessionType = clientMakers.remove(key).typeToMake();
							if (sessionType == null)
								throw new IllegalStateException("Invalid session type " + sessionType);

							switch (model.getLocalType()) {
								case ENTRY_NODE:
									close("Failed to establish connection with " + ClientType.CENTRAL_RELAY, ex);
									break;
								case EXIT_NODE:
									switch (sessionType) {
										case UPWARDS_RELAY:
											close("Failed to establish connection with " + ClientType.CENTRAL_RELAY, ex);
											break;
										case TERMINUS:
											close("Failed to establish connection with terminus", ex);
											break;
										default:
											throw new IllegalStateException("Invalid session type " + sessionType);
									}
									break;
								default:
									throw new IllegalStateException("Invalid client type " + model.getLocalType());
							}
						} catch (Throwable ex) {
							// the show must go on. don't let any single iteration spoil our event loop.
							if (session != null)
								LOG.log(Level.WARNING, "Error while processing packet from " + session.getModel().getRemoteTypeString(), ex);
							else if (client != null && client.socket() != null)
								LOG.log(Level.WARNING, "Error while processing packet from " + client.socket().getRemoteSocketAddress(), ex);
							else
								LOG.log(Level.WARNING, "Error while processing packet", ex);
						}
					}
				}
			} catch (IOException ex) {
				close(ex.getMessage(), ex);
			}
			cleanupAll(connected);
		}
	}

	private final LocalRouter model;

	private final AtomicBoolean closeEventsTriggered;
	private final EventLoopTask eventLoop;

	public ClientManagerNio(LocalRouter thisState) {
		model = thisState;
		closeEventsTriggered = new AtomicBoolean(false);
		eventLoop = new EventLoopTask();
		eventLoopThreadPool.submit(eventLoop);
	}

	@Override
	public void close(String reason, Throwable reasonExc) {
		if (closeEventsTriggered.compareAndSet(false, true)) {
			model.dispose();
			eventLoop.closeSelector();
			if (reasonExc == null)
				LOG.log(Level.INFO, "Network event selector closed ({0})", reason);
			else
				LOG.log(Level.INFO, "Network event selector closed (" + reason + ")", reasonExc);
			eventLoopThreadPool.shutdown();
		}
	}

	// TODO: properties is not type-safe and a code smell. replace functionality with polymorphism somehow.
	// FIXME: internalNodeFactory for central relay, externalNodeFactory for entry node
	@Override
	public void listen(RemoteNode.RemoteNodeFactory clientMaker, int port, Map<String, Object> properties) {
		SocketAddress address = new InetSocketAddress(port);
		try {
			ServerSocketChannel listener = ServerSocketChannel.open();
			listener.socket().bind(address);
			listener.configureBlocking(false);

			eventLoop.addAcceptor(address, clientMaker, listener, properties);
		} catch (IOException ex) {
			close("Could not bind on " + address, ex);
		}
	}

	// TODO: properties is not type-safe and a code smell. replace functionality with polymorphism somehow.
	// FIXME: externalNodeFactory for exit node -> terminus,
	// downwardsRelayFactory for entry node -> central relay, upwardsRelayFactory for exit node -> central relay
	@Override
	public void connect(RemoteNode.RemoteNodeFactory clientMaker, String host, int port, Map<String, Object> properties) {
		SocketAddress address = new InetSocketAddress(host, port);
		try {
			SocketChannel speaker = SocketChannel.open();
			speaker.configureBlocking(false);
			speaker.connect(address);

			eventLoop.addConnector(address, clientMaker, speaker, properties);
		} catch (IOException ex) {
			close("Could not connect to " + address, ex);
		}
	}

	@Override
	public boolean isShutdown() {
		return closeEventsTriggered.get();
	}
}

# nat-ladder
A reverse connection suite. Access a network with an inbound firewall by initiating an outbound connection to an intermediary proxy that forwards inbound connections. Consists of an inbound client, a proxy server, and an outbound client.

An inbound client must register with the proxy server an accessible host and port on its intranet to which it forwards any inbound connections to. A password protects unauthorized users from sending arbitrary packets to this intranet endpoint. The user must input the host IP and port to forward to, the address of the proxy server to register on, the password to authenticate, and a unique name that identifies the inbound client so that an outbound client can initiate a connection to it.

An outbound client must input the identifying name of the inbound client it wishes to connect to and the authentication password. The outbound client will then listen on a local port so that other local and intranet applications can connect to the outbound client and be able to communicate with the inbound client as if the remote application is listening on a local port.

The proxy server handles the authentication and handshake between outbound and inbound clients and forwards the packets received from the outbound client to the inbound client.

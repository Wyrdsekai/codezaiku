# WebSocket Patterns

## When to use
- Real-time bidirectional communication (chat, collaborative editing, live dashboards)
- Server-initiated push where SSE is insufficient (client also needs to send structured messages)
- Low-latency streaming where HTTP request overhead is unacceptable

## Pattern

### Connection lifecycle
1. **Handshake**: HTTP upgrade request; validate auth (token in query param or first message) during upgrade
2. **Open**: connection established; server sends an initial state or acknowledgment message
3. **Messaging**: structured messages (JSON or binary) flow in both directions
4. **Close**: either side sends a close frame with a status code; clean shutdown
- Use close codes: `1000` (normal), `1001` (going away), `1008` (policy violation), `1011` (server error)

### Message protocol design
- Define a message envelope: `{ "type": "chat.message", "payload": { ... }, "id": "msg_123" }`
- `type` field enables routing to different handlers
- `id` field enables acknowledgment, deduplication, and request-response pairing
- Version the protocol: include a `version` field or negotiate during handshake

### Heartbeats and keepalive
- Send ping/pong frames at regular intervals (30-60 seconds)
- Detect dead connections: if no pong received within timeout, close and clean up
- WebSocket protocol has built-in ping/pong frames; some frameworks also use application-level heartbeats
- Required to prevent idle connection timeouts from proxies and load balancers

### Reconnection strategy (client-side)
1. On unexpected close or error, wait with exponential backoff (1s, 2s, 4s, 8s... capped at 30s)
2. Add jitter (random 0-1s) to prevent thundering herd on server restart
3. On reconnect, send the last received message ID or timestamp to request missed messages
4. Limit total reconnection attempts; after N failures, surface the error to the user

### Server-side state management
- Map connections to user IDs / sessions in an in-memory registry
- On disconnect, mark the user as offline after a grace period (handles brief reconnects)
- For multi-server deployments, use a pub/sub backbone (Redis, NATS) to broadcast messages to all nodes
- Each server subscribes to channels relevant to its connected users

### Scaling WebSocket servers
- WebSocket connections are stateful and long-lived — they pin to a specific server
- Sticky sessions (IP hash or cookie-based) at the load balancer for reconnection stability
- Horizontal scaling: pub/sub backbone ensures messages reach the correct server
- Monitor connection count per server; set connection limits to prevent resource exhaustion
- Consider connection pooling for server-to-server WebSocket communication

### Security
- Authenticate during the upgrade handshake, not after the connection is open
- Validate and sanitize all incoming messages — WebSocket data is untrusted input
- Rate limit messages per connection (not just per HTTP request)
- Use `wss://` (TLS) exclusively; never `ws://` in production
- Implement origin checking to prevent cross-site WebSocket hijacking

## Gotchas / Anti-patterns
- **No heartbeats**: connections silently die behind proxies; both sides think they are still connected
- **Auth token in the URL**: query parameters appear in server logs and proxy logs; use a short-lived token or authenticate via the first message
- **Unbounded message queues**: server buffers messages for a slow client until OOM; implement backpressure or drop old messages
- **Single server assumption**: works in development; fails immediately when a second server instance is added
- **Using WebSocket when SSE suffices**: if only the server pushes data, SSE is simpler, auto-reconnects, and works through HTTP proxies

## References
- RFC 6455: The WebSocket Protocol
- MDN: WebSocket API
- Socket.IO documentation: rooms, namespaces, reconnection (framework-specific but patterns are transferable)
- IETF: WebSocket protocol considerations for security

# gRPC Patterns

## When to use
- Service-to-service communication where performance and type safety matter
- Streaming requirements (server-push, client-push, or bidirectional)
- Polyglot environments where generated client/server stubs prevent drift
- Internal APIs where browser compatibility is not required (or use gRPC-Web/Connect)

## Pattern

### Proto file design
- One service per proto file; group by domain boundary (e.g., `order_service.proto`)
- Use specific request/response messages per RPC — not generic wrappers
- Every RPC gets its own `XxxRequest` and `XxxResponse` message even if initially empty (enables future evolution)
- Package naming: `package company.team.service.v1;` — include version in the package
- Field numbering: never reuse a deleted field number; reserve it

### Streaming patterns
- **Unary**: request-response; use for most RPCs
- **Server streaming**: server sends a stream of messages; use for feeds, large result sets, long-running progress
- **Client streaming**: client sends a stream; use for file upload, telemetry ingestion
- **Bidirectional streaming**: both sides stream independently; use for chat, collaborative editing, live sync
- Always send an initial message to confirm the stream is established before flowing data

### Deadline propagation
- Every RPC call should have a deadline (timeout) — never infinite
- Propagate deadlines across service boundaries: if service A calls B with 5s left, B should respect that
- Check `context.Err()` / `Context.isCancelled()` before starting expensive work
- Default deadlines: set at the edge (API gateway or client), propagate inward

### Error handling
- Use canonical gRPC status codes: `OK`, `INVALID_ARGUMENT`, `NOT_FOUND`, `ALREADY_EXISTS`, `PERMISSION_DENIED`, `INTERNAL`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`
- Attach structured error details via `google.rpc.Status` and `google.rpc.ErrorInfo`
- `UNAVAILABLE` = transient, client should retry. `INTERNAL` = bug, do not retry. `INVALID_ARGUMENT` = client error, fix the request
- Map gRPC codes to HTTP status codes at the gateway for REST clients

### Interceptors (middleware)
- Use interceptors for cross-cutting concerns: logging, metrics, auth, tracing, retry
- Chain order matters: auth before logging, tracing wraps everything
- Server interceptors: validate auth tokens, extract metadata, enforce rate limits
- Client interceptors: inject auth headers, add tracing spans, implement retry with backoff

### API evolution
- Add new fields freely (backward compatible as long as field numbers are unique)
- Never change a field's type or number
- Deprecate fields with `[deprecated = true]`; remove in the next major version
- Major versions: `v1`, `v2` as separate packages; run both concurrently during migration

## Gotchas / Anti-patterns
- **No deadlines**: a missing deadline means a stuck downstream call hangs forever; always set one
- **Generic request wrappers**: `message GenericRequest { bytes payload = 1; }` defeats the purpose of protobuf type safety
- **Ignoring backpressure in streams**: producing faster than the consumer can handle; respect flow control
- **Reusing field numbers after deletion**: causes silent data corruption for clients on the old schema
- **Exposing gRPC directly to browsers**: standard gRPC requires HTTP/2 trailers; use gRPC-Web or Connect for browser clients

## References
- gRPC official documentation (grpc.io)
- Protocol Buffers Language Guide (proto3)
- Google API Design Guide (aip.dev)
- Connect RPC (connectrpc.com): browser-compatible gRPC

# Integration Patterns

## When to use
- Connecting systems with different data models, protocols, or life cycles
- Migrating from monolith to microservices incrementally
- Building a front-end that consumes multiple backend services
- Protecting domain models from external system leakage

## Pattern

### API Gateway
- Single entry point for all client requests; routes to appropriate backend services
- Responsibilities: request routing, authentication, rate limiting, TLS termination, request/response transformation
- Aggregate responses from multiple services into a single client response (composition)
- Protocol translation: accept REST from clients, route to gRPC/message queue backends
- Keep the gateway thin: routing and cross-cutting concerns only; no business logic in the gateway
- Health checks: gateway monitors backend health and returns appropriate errors (503) for unavailable services

### Backend for Frontend (BFF)
- Dedicated backend per client type (web, mobile, third-party API) tailored to that client's needs
- Web BFF returns HTML-friendly payloads; mobile BFF returns compact JSON; partner BFF follows their integration spec
- Each BFF can aggregate, filter, and reshape data from shared backend services
- Owned by the frontend team: they control the API contract they consume
- Avoids "one API fits all" where mobile gets too much data and web gets too little

### Anti-Corruption Layer (ACL)
- Translation boundary between your domain model and an external system's model
- Adapter: converts external system's data formats and protocols to your domain's language
- Facade: simplifies the external system's complex API into what your domain actually needs
- Place the ACL in a separate module/service: isolate external system changes from your domain
- Two-way translation: commands going out are translated from your model to theirs; responses coming in are translated back
- When the external system changes, only the ACL changes; domain model remains stable

### Strangler Fig Migration
- Incrementally replace a legacy system by routing requests to new implementations feature by feature
- Facade/proxy sits in front of the legacy system; routes specific paths to the new system, everything else to legacy
- Migration steps: identify a feature, implement in new system, route traffic, verify, decommission legacy feature
- Data synchronization: during migration, keep legacy and new system in sync (dual writes or change data capture)
- Rollback: the proxy can route back to legacy if the new implementation has issues
- Complete when all routes point to the new system; then remove the proxy and legacy

### Shared Data Patterns
- **Database per service**: each service owns its data; communicate via APIs or events (preferred for autonomy)
- **Shared database**: multiple services read/write the same database (simpler but couples deployment and schema changes)
- **Change Data Capture (CDC)**: capture row-level changes from one database and publish as events for other services
- **Event-carried state transfer**: events contain the changed data; consumers build local copies for query (eventually consistent)
- Avoid distributed transactions (2PC) across services; use sagas for cross-service consistency

### Synchronous vs Asynchronous Integration
- **Synchronous (request/response)**: simple, immediate feedback, but couples availability (if downstream is down, upstream fails)
- **Asynchronous (messaging)**: decouples availability and absorbs load spikes, but adds complexity (eventual consistency, message ordering)
- Rule of thumb: synchronous for queries where the user is waiting; asynchronous for commands where the user does not need immediate confirmation
- Circuit breaker on synchronous calls: detect failing downstream, fail fast, recover automatically when downstream is healthy

### Contract Management
- Consumer-driven contracts: consumers define what they need; providers verify they do not break those contracts
- Schema registry for event schemas (Avro, Protobuf): enforce backward compatibility on every schema change
- API versioning: URL path (`/v2/orders`) or header-based; support N-1 version minimum during transition
- Contract tests in CI: fail the build if a provider change breaks a consumer contract

## Gotchas / Anti-patterns
- **Smart gateway**: putting business logic, transformation rules, or orchestration in the API gateway
- **Leaky abstraction in ACL**: letting external system's error codes, IDs, or terminology leak into your domain
- **Big bang migration**: replacing the entire legacy system at once instead of incrementally (high risk, long delay)
- **Distributed monolith**: microservices that require synchronized deployment; you have the complexity of distributed systems with none of the benefits
- **No circuit breaker**: synchronous chain of 5 services; one failure cascades to all; use circuit breakers and bulkheads
- **Shared database coupling**: one team's schema migration breaks another team's service at deploy time

## References
- Hohpe & Woolf, "Enterprise Integration Patterns" — foundational integration catalog
- Newman, "Building Microservices" (2nd ed.) — integration strategies for microservices
- Richardson, "Microservices Patterns" — API gateway, BFF, saga, and CQRS patterns
- Fowler, "StranglerFigApplication" — https://martinfowler.com/bliki/StranglerFigApplication.html
- Microsoft Azure Architecture Center — cloud integration pattern documentation

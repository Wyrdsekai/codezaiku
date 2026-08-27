# CQRS (Command Query Responsibility Segregation)

## When to use
- Read and write workloads have fundamentally different shapes, scales, or optimization needs
- Multiple read representations are needed from the same data (list, search, analytics, reporting)
- System benefits from independent scaling of read and write sides
- Combined with event sourcing for full audit trail plus optimized query models

## Pattern

### Command Side (Write Model)
- Commands represent intent: `PlaceOrder`, `CancelSubscription`, `ApproveRefund`
- Validate commands against business rules in the domain model (aggregate)
- Commands produce events or state changes; commands themselves are not persisted (events are)
- One command handler per command type; handler loads aggregate, executes business logic, persists result
- Return: success/failure (and optionally the new aggregate version); do not return read model data from command handlers

### Query Side (Read Model)
- Queries are side-effect-free: `GetOrderDetails`, `SearchProducts`, `ListRecentTransactions`
- Read models are purpose-built data structures optimized for specific queries (denormalized, pre-joined, indexed)
- Each read model can use a different storage technology: relational for complex queries, search engine for full-text, cache for hot data
- Read models are populated asynchronously from events or change data capture from the write store
- Multiple read models from the same source: each subscribes to relevant events and builds its own projection

### Eventual Consistency Management
- Accept that read models lag behind writes; the delay is typically milliseconds to seconds
- **Read-your-writes**: after a command, the UI can optimistically update locally or poll until the projection catches up
- **Causal consistency**: include the write version in the command response; query side can wait until it has processed that version
- **Stale data indicators**: display "as of" timestamps on read-heavy dashboards
- Design business processes to tolerate eventual consistency; if strong consistency is required for a specific query, read from the write model directly

### Command Validation
- **Structural validation**: required fields, types, ranges — validate before reaching the domain
- **Business rule validation**: domain invariants, authorization, cross-entity constraints — validate in the aggregate or command handler
- **Idempotency**: assign command IDs; detect and deduplicate repeated commands (network retries, user double-clicks)
- Return meaningful error responses: distinguish validation failures, conflict (concurrent modification), and authorization errors

### Separation Strategies
- **Logical separation**: same process, different code paths for commands and queries; simplest starting point
- **Separate models**: different database schemas or tables for write and read; same database server
- **Separate stores**: different databases entirely (e.g., PostgreSQL for writes, Elasticsearch for search queries)
- **Separate services**: command service and query service as independent deployable units; full independence, highest complexity
- Start with logical separation; split further only when scaling or complexity demands it

## Gotchas / Anti-patterns
- **CQRS everywhere**: applying CQRS to simple CRUD domains adds complexity with no benefit
- **Queries on the write model**: bypassing the read model for convenience defeats the optimization purpose
- **Commands returning query data**: "create and return full object" couples the two sides; return ID/version only
- **Ignoring eventual consistency in UX**: users see stale data and think the operation failed; design for it
- **Single read model for all queries**: one giant denormalized table becomes a bottleneck; create focused read models per use case
- **No idempotency**: command retries cause duplicate side effects (double charges, duplicate emails)

## References
- Young, "CQRS Documents" — original CQRS description
- Fowler, "CQRS" — https://martinfowler.com/bliki/CQRS.html
- Microsoft, "CQRS Pattern" — Azure architecture patterns documentation
- Kleppmann, "Designing Data-Intensive Applications" — materialized views and derived data
- Axon Framework / Lagom / EventStoreDB — frameworks implementing CQRS patterns

# Event Sourcing

## When to use
- System requires complete audit trail of every state change
- Need to reconstruct historical state at any point in time
- Multiple read models need different views of the same data
- Domain events are a natural way to express business processes (orders, transactions, workflows)

## Pattern

### Event Store
- Append-only log: events are immutable facts; never update or delete events
- Each event contains: aggregate ID, event type, payload, timestamp, sequence number, metadata (user, correlation ID)
- Stream per aggregate: events for one entity (e.g., Order-12345) stored and read as a unit
- Optimistic concurrency: write with expected version number; reject if stream has advanced (prevents lost updates)
- Global ordering: assign a global position to each event for cross-aggregate subscriptions

### Event Design
- Events describe what happened, past tense: `OrderPlaced`, `PaymentReceived`, `ItemShipped`
- Include all data needed to interpret the event without looking up external state
- Keep events small and focused: one business fact per event
- Use explicit types (not generic "EntityUpdated"): readers should process events by type without parsing payload
- Schema: define events as versioned schemas; include a version field or use schema registry

### Projections (Read Models)
- Subscribe to event stream; apply events to build a read-optimized data structure
- Projections are disposable: they can be rebuilt from scratch by replaying the event stream
- Multiple projections from the same events: list view, search index, analytics aggregation, notification trigger
- Projection position tracking: store the last processed event position; resume from there after restart
- Eventual consistency: projections lag behind writes; design UIs and APIs to tolerate this (read-your-writes where critical)

### Snapshots
- For aggregates with many events, replaying from the beginning is slow
- Periodically serialize the current aggregate state as a snapshot event
- Load: read snapshot + events after snapshot position; apply incrementally
- Snapshot frequency: every N events (e.g., 100) or on a schedule
- Snapshots are an optimization, not a replacement: the event stream remains the source of truth

### Event Versioning
- **Upcasting**: transform old event format to new format on read (in-memory, at deserialization time)
- **New event type**: introduce `OrderPlacedV2` alongside `OrderPlaced`; projections handle both
- **Weak schema**: add optional fields with defaults; existing events remain valid
- Never modify persisted events: all evolution happens through new events or read-time transformation
- Migration strategy: for major schema changes, write a one-time projection that emits new-format events to a new stream

## Gotchas / Anti-patterns
- **Events as CRUD log**: `OrderUpdated { fields: {...} }` loses the business intent; use specific event types
- **Huge event payloads**: embedding large blobs in events; store references (URLs, IDs) and keep events lean
- **Missing correlation IDs**: without them, tracing a business process across aggregates and services is impossible
- **Snapshot-only recovery**: if snapshots are corrupt and events are deleted, state is lost; always keep the full event stream
- **Synchronous projections**: blocking the write path on projection updates defeats the purpose; project asynchronously
- **No event versioning strategy from day one**: changing event schemas later without a plan is painful

## References
- Fowler, "Event Sourcing" — https://martinfowler.com/eaaDev/EventSourcing.html
- Young, "CQRS and Event Sourcing" — foundational talks and blog posts
- Kleppmann, "Designing Data-Intensive Applications" — ch. 11 on stream processing
- EventStoreDB documentation — purpose-built event store with projections
- Axon Framework documentation — JVM event sourcing framework

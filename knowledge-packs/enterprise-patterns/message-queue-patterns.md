# Message Queue Patterns

## When to use
- Decoupling producers and consumers that operate at different speeds or availability levels
- Distributing work across multiple consumers for parallel processing
- Ensuring reliable delivery of messages even when consumers are temporarily unavailable
- Building event-driven architectures where components react to published events

## Pattern

### Pub/Sub (Publish-Subscribe)
- Publisher sends messages to a topic; all subscribers receive a copy
- Fan-out: one event triggers processing in multiple independent services (order placed -> email, inventory, analytics)
- Subscriber groups: within a group, only one member receives each message (load balancing within a concern)
- Topic design: one topic per event type or per bounded context; avoid catch-all topics
- Filtering: subscribers can filter by message attributes to receive only relevant messages without topic proliferation

### Competing Consumers
- Multiple consumer instances read from the same queue; each message processed by exactly one consumer
- Enables horizontal scaling: add consumers to increase throughput
- Message acknowledgment: consumer acks after successful processing; unacked messages return to queue for redelivery
- Visibility timeout: message hidden from other consumers while being processed; if not acked within timeout, message reappears
- Prefetch count: limit messages pulled per consumer to prevent one slow consumer from starving others

### Dead Letter Queue (DLQ)
- Messages that fail processing after N retries are moved to a DLQ instead of being discarded or retried forever
- DLQ preserves the original message, failure reason, and retry count for investigation
- Monitor DLQ depth: non-zero depth is an alert condition requiring human attention
- Reprocessing: fix the bug, then replay DLQ messages back to the main queue
- Separate DLQ per source queue: maintains context about which processing stage failed

### Message Ordering
- **Partition-based ordering**: messages with the same partition key are ordered within a partition (Kafka, Kinesis)
- **FIFO queues**: strict ordering across all messages in a queue (SQS FIFO, limited throughput)
- **No global ordering**: most queue systems do not guarantee ordering across partitions; design for it
- Partition key choice: entity ID (order ID, user ID) ensures events for one entity are ordered
- Trade-off: strict ordering limits parallelism; only require ordering where business logic demands it

### Delivery Guarantees
- **At-most-once**: fire and forget; messages may be lost but never duplicated (logging, metrics)
- **At-least-once**: retry until ack; messages may be duplicated (default for most queues)
- **Exactly-once**: at-least-once delivery + idempotent consumer; the consumer deduplicates (use idempotency keys)
- Most systems provide at-least-once; exactly-once semantics are the consumer's responsibility
- Idempotent consumer pattern: store processed message IDs; skip duplicates on redelivery

### Backpressure and Flow Control
- Consumer-side: limit prefetch/batch size; reject or slow-poll when overwhelmed
- Producer-side: queue depth monitoring; alert or throttle producers when queue grows beyond threshold
- Buffering: queues absorb traffic spikes; size the queue for expected burst duration
- Scaling trigger: auto-scale consumers based on queue depth or message age (oldest unprocessed message)

### Message Design
- Include: event type, payload, timestamp, correlation ID, source, schema version
- Keep messages self-contained: consumer should not need to call back to producer for context
- Use schema evolution (Avro, Protobuf, JSON Schema) with a registry for backward/forward compatibility
- Avoid large payloads: store large data (files, images) externally; include a reference (URL, S3 key) in the message

## Gotchas / Anti-patterns
- **Queue as database**: using queues for long-term storage; queues are for transit, not persistence
- **Message ordering assumptions**: assuming ordered delivery from a non-ordered queue; test with concurrent consumers
- **No DLQ**: poison messages retry forever, blocking the queue for all consumers
- **Unbounded retries without backoff**: immediate retries on transient failures cause thundering herd on the downstream service
- **Large messages**: 256KB+ messages cause throughput issues; use claim-check pattern (store payload externally)
- **Missing correlation IDs**: cannot trace a request across producer, queue, and consumer without correlation

## References
- Hohpe & Woolf, "Enterprise Integration Patterns" — canonical messaging pattern catalog
- Kleppmann, "Designing Data-Intensive Applications" — ch. 11 on messaging and stream processing
- AWS SQS/SNS, Apache Kafka, RabbitMQ documentation — implementation-specific guidance
- CloudEvents specification — standardized event metadata format

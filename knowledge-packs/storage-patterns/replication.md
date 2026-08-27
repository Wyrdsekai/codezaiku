# Replication Patterns

## When to use
- Designing a distributed database or storage system for high availability
- Choosing a replication topology for a specific workload profile
- Implementing consensus for strong consistency across replicas
- Reasoning about read-after-write consistency and conflict resolution

## Pattern

### Leader-Follower (Primary-Replica)
- One leader accepts all writes; followers replicate the leader's write log asynchronously or synchronously
- Reads can be served by any follower (eventually consistent) or by the leader (strongly consistent)
- Failover: if the leader fails, promote a follower; this requires detecting the failure and agreeing on the new leader
- **Synchronous replication**: the leader waits for at least one follower to confirm before acknowledging the write — durable but higher latency
- **Asynchronous replication**: the leader acknowledges immediately — lower latency but risk of data loss if the leader crashes before replication
- **Semi-synchronous**: one follower is synchronous (guarantees at least one copy), the rest are asynchronous

### Multi-Leader (Multi-Primary)
- Multiple nodes accept writes independently; changes are replicated bidirectionally
- Useful for: multi-datacenter deployments (one leader per datacenter) or offline-capable clients
- Write conflicts arise when two leaders concurrently modify the same record
- Conflict resolution strategies:
  - **Last-writer-wins (LWW)**: use timestamps; later write wins. Simple but loses data silently.
  - **Merge function**: application-defined logic to reconcile (e.g., union of sets, CRDT merge)
  - **Conflict flagging**: store both versions, let the user resolve (like Git merge conflicts)
- Avoid multi-leader unless you genuinely need it — conflict resolution is a source of subtle bugs

### Leaderless (Dynamo-Style)
- Any node accepts reads and writes; the client sends requests to multiple nodes in parallel
- **Quorum**: write to W nodes, read from R nodes; if `W + R > N`, at least one read node has the latest write
- Common configuration: N=3, W=2, R=2 (tolerates one node failure for both reads and writes)
- **Read repair**: if a read detects stale data on some nodes, send the latest version back to them
- **Anti-entropy**: background process compares data across nodes and reconciles differences
- Sloppy quorum / hinted handoff: if a target node is down, write to a temporary stand-in; hand off when the target recovers

### Consensus Protocols
- **Raft**: leader-based; leader election via randomized timeouts; log replication to a majority; straightforward to implement
- **Paxos**: classic consensus; more general than Raft but harder to implement correctly; Multi-Paxos for repeated consensus
- **ZAB (ZooKeeper Atomic Broadcast)**: similar to Raft, used by ZooKeeper
- **EPaxos / Mencius**: leaderless consensus variants for multi-datacenter deployments
- Consensus guarantees: agreement (all correct nodes decide the same value), validity (the decided value was proposed), termination (a decision is eventually reached)

### Replication Lag and Consistency Guarantees
- **Read-after-write consistency**: a user always sees their own writes (route reads to the leader, or wait for replication)
- **Monotonic reads**: a user never sees time go backward (pin a user to one replica)
- **Consistent prefix reads**: causally related writes are seen in order (track causal dependencies)
- Measure and monitor replication lag — it is the source of most "weird behavior" complaints in replicated systems

### Change Data Capture (CDC)
- Stream the write log (WAL) to external consumers (search indexes, caches, analytics)
- CDC is the foundation for keeping derived data stores in sync with the source of truth
- Tools: Debezium (for PostgreSQL, MySQL, MongoDB), Maxwell, PostgreSQL logical replication
- Treat the write log as an event stream — downstream consumers are subscribers

## Gotchas / Anti-patterns
- **Assuming async replication is strongly consistent**: reads from followers may return stale data
- **Split-brain on leader failover**: two nodes both believe they are the leader — use fencing tokens or consensus
- **LWW conflict resolution with unsynchronized clocks**: clock skew means "last" is arbitrary, not causal
- **Quorum with sloppy writes**: sloppy quorum (hinted handoff) does not guarantee overlap between read and write sets — consistency is weaker than it appears
- **Ignoring replication lag in the application**: "I just wrote this, why can't I see it?" — implement read-after-write at the application level
- **Replicating without monitoring**: you discover replication is 3 hours behind only when a user complains

## References
- Kleppmann, M. "Designing Data-Intensive Applications", Chapter 5 (O'Reilly, 2017)
- Raft consensus algorithm: https://raft.github.io/
- Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm" (2014)
- Debezium (CDC platform): https://debezium.io/
- Jepsen consistency analysis: https://jepsen.io/

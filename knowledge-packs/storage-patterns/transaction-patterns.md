# Transaction Patterns

## When to use
- Implementing or choosing a transactional storage engine
- Reasoning about isolation levels and their trade-offs
- Debugging anomalies (dirty reads, phantom reads, write skew)
- Designing an application's concurrency control strategy

## Pattern

### ACID Properties
- **Atomicity**: a transaction either fully commits or fully rolls back — no partial results
- **Consistency**: transactions transition the database from one valid state to another (application-defined invariants hold)
- **Isolation**: concurrent transactions do not observe each other's intermediate states
- **Durability**: once committed, data survives crashes (persisted to non-volatile storage)

### Isolation Levels (weakest to strongest)
1. **Read Uncommitted**: can see uncommitted writes from other transactions (dirty reads). Rarely useful.
2. **Read Committed**: only sees committed data. Prevents dirty reads. Each query sees the latest committed snapshot (so re-reading may return different results within one transaction).
3. **Repeatable Read / Snapshot Isolation**: the transaction sees a consistent snapshot taken at transaction start. Prevents dirty reads and non-repeatable reads. May still allow write skew.
4. **Serializable**: transactions execute as if they ran one at a time. Prevents all anomalies including write skew and phantom reads.

### Write Skew
- Two transactions read the same rows, make decisions based on what they read, and write different rows — the combined result violates an invariant
- Example: two doctors both check that at least one doctor is on call, each decides to remove themselves — no one is on call
- Snapshot isolation does not prevent this; serializable isolation does
- Application-level mitigation: `SELECT ... FOR UPDATE` to lock the rows that inform the decision

### MVCC (Multi-Version Concurrency Control)
- Each write creates a new version of the row, tagged with the transaction ID
- Readers see the version that was current at their snapshot timestamp — they never block writers
- Writers only block other writers to the *same* row (write-write conflict)
- Garbage collection removes old versions that no active transaction can see
- Used by: PostgreSQL, MySQL/InnoDB, Oracle, CockroachDB, SQLite (WAL mode)

### Write-Ahead Logging (WAL)
- Before modifying a data page, write the intended change to a sequential log file
- On crash, replay the log to restore committed changes and undo uncommitted ones
- The log is append-only and sequential — fast on both HDD and SSD
- Checkpointing: periodically flush dirty pages to the data files so the log can be truncated
- WAL is the standard durability mechanism in nearly all relational databases

### Optimistic vs Pessimistic Concurrency
- **Pessimistic (locking)**: acquire locks before reading/writing; other transactions wait. Simple but can deadlock and limits concurrency.
- **Optimistic (validation)**: execute without locks, validate at commit time that no conflicts occurred; abort and retry on conflict. Better throughput when conflicts are rare.
- **Hybrid**: use MVCC for reads (optimistic) and locks for writes (pessimistic) — this is what most databases do.

### Two-Phase Commit (2PC)
- Coordinates a transaction across multiple participants (databases, services)
- Phase 1 (prepare): coordinator asks all participants to prepare; each responds yes/no
- Phase 2 (commit/abort): if all said yes, coordinator sends commit; if any said no, sends abort
- Blocking protocol: if the coordinator crashes after prepare but before commit, participants are stuck holding locks
- Mitigate: use a persistent transaction log on the coordinator; implement timeout-based heuristic resolution

### Serializable Snapshot Isolation (SSI)
- Extends snapshot isolation to be fully serializable
- Tracks read and write dependencies between transactions
- At commit time, detects dangerous dependency cycles (potential serialization anomalies) and aborts one transaction
- Lower overhead than traditional two-phase locking serializability
- Used by: PostgreSQL (SERIALIZABLE level), CockroachDB

## Gotchas / Anti-patterns
- **Assuming READ COMMITTED is enough**: write skew and phantom reads can silently corrupt business logic
- **Long-running transactions**: hold MVCC snapshots alive, preventing garbage collection and bloating storage
- **Deadlocks from inconsistent lock ordering**: always acquire locks in a deterministic order (e.g., by primary key)
- **Ignoring transaction retries**: optimistic concurrency and SSI will abort transactions — the application must retry
- **2PC with unreliable coordinator**: if the coordinator's log is lost, participants are stuck in an uncertain state
- **Autocommit for multi-statement logic**: each statement is its own transaction, so the sequence is not atomic

## References
- Kleppmann, M. "Designing Data-Intensive Applications", Chapter 7 (O'Reilly, 2017)
- Berenson et al., "A Critique of ANSI SQL Isolation Levels" (1995)
- PostgreSQL transaction isolation: https://www.postgresql.org/docs/current/transaction-iso.html
- CockroachDB serializable transactions: https://www.cockroachlabs.com/docs/stable/transactions.html
- CMU Database Group, Concurrency Control lectures: https://15445.courses.cs.cmu.edu/

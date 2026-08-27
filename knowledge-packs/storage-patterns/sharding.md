# Sharding (Partitioning) Patterns

## When to use
- A single node cannot hold or serve the entire dataset
- Scaling write throughput beyond what a single leader can handle
- Distributing data across nodes for locality (geographic or workload-based)
- Designing a distributed database, cache, or storage system

## Pattern

### Range Sharding
- Assign contiguous ranges of the shard key to each partition: shard 1 holds A-F, shard 2 holds G-M, etc.
- Supports efficient range scans: a query on a key range touches only the relevant shards
- Risk: hot spots if the key distribution is skewed (e.g., all recent timestamps land on one shard)
- Mitigate by choosing a shard key with even distribution, or by splitting hot ranges dynamically
- Used by: HBase, CockroachDB, TiKV, Spanner

### Hash Sharding
- Hash the shard key and assign hash ranges to partitions: `shard = hash(key) mod N`
- Distributes data evenly regardless of key distribution — eliminates most hot spots
- Destroys key ordering: range queries must scatter to all shards and merge results
- Consistent hashing: nodes own ranges on a hash ring; adding/removing a node moves only 1/N of the keys
- Used by: Cassandra, DynamoDB, Redis Cluster, Riak

### Choosing a Shard Key
- The shard key determines which partition holds each record — it is the most important design decision
- Good shard keys: high cardinality, evenly distributed, present in most queries (avoids scatter-gather)
- Composite shard keys: `(tenant_id, timestamp)` — range within a tenant on one shard, even tenant distribution
- Avoid monotonically increasing keys (auto-increment IDs, timestamps) for hash sharding — they concentrate writes on one shard with range sharding unless you add a random prefix

### Resharding (Rebalancing)
- Adding or removing nodes requires moving data between partitions
- **Fixed partition count**: pre-create many more partitions than nodes (e.g., 1000 partitions for 10 nodes); reassign partitions to new nodes without splitting
- **Dynamic splitting**: split a partition when it grows too large; merge when it shrinks; rebalance assignments
- **Consistent hashing with virtual nodes**: each physical node owns many virtual nodes on the ring; adding a node takes proportional virtual nodes from existing nodes
- Rebalancing should be gradual and not block reads/writes — copy data in the background, then switch

### Cross-Shard Queries
- Queries that span multiple shard keys require scatter-gather: send the query to all relevant shards, merge results
- Scatter-gather is expensive — design the schema so the most common queries hit a single shard
- Cross-shard joins are especially costly; denormalize or co-locate related data on the same shard
- Cross-shard transactions require distributed coordination (2PC or similar) — avoid if possible

### Secondary Indexes on Sharded Data
- **Local (document-partitioned) index**: each shard indexes only its own data; queries on the secondary index scatter to all shards
- **Global (term-partitioned) index**: the index itself is sharded by the indexed term; a query on the secondary index goes to one index shard, which points to data shards
- Global indexes are faster for reads but slower for writes (each write may update an index on a different shard)
- Most systems use local indexes for simplicity and accept the scatter-gather cost

### Hot Spot Mitigation
- If a single key is extremely hot (celebrity's profile, viral content), even hash sharding concentrates load
- Add a random prefix to the key to spread writes across shards: `shard_key = random(0..9) + actual_key`
- Reads must then query all prefixed variants and merge — trade write distribution for read fan-out
- Application-level caching in front of the hot shard is often the simplest mitigation

## Gotchas / Anti-patterns
- **Sharding too early**: sharding adds complexity; do not shard until a single node is genuinely insufficient
- **Wrong shard key**: choosing a shard key that does not appear in the WHERE clause of common queries forces scatter-gather everywhere
- **Assuming even distribution**: real-world data is skewed; test with realistic data, not synthetic uniform data
- **Cross-shard transactions as the norm**: if most operations need distributed transactions, the sharding scheme is wrong
- **Manual resharding**: requiring downtime to add a node; design for online rebalancing from the start
- **Ignoring co-location**: putting a user's data on one shard and their orders on another guarantees cross-shard joins

## References
- Kleppmann, M. "Designing Data-Intensive Applications", Chapter 6 (O'Reilly, 2017)
- Consistent hashing (Karger et al., 1997): https://dl.acm.org/doi/10.1145/258533.258660
- CockroachDB range partitioning: https://www.cockroachlabs.com/docs/stable/partitioning.html
- Cassandra partitioning: https://cassandra.apache.org/doc/latest/cassandra/architecture/partitioning.html
- Vitess (MySQL sharding): https://vitess.io/

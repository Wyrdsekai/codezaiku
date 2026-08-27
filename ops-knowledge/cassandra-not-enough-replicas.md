match: cassandra, scylla, scylladb
signature: cannot achieve consistency level, not enough replicas available, unavailableexception
push: rescue
status: candidate
platform: systemd
# Cassandra not enough replicas — fix procedure
1. "Cannot achieve consistency level QUORUM" / "Not enough replicas available for query" (UnavailableException) = one or more replica nodes for the queried keyspace are down, so the consistency level cannot be met.
2. Find the down nodes: `nodetool status` — rows marked "DN" (Down/Normal) are the offline replicas.
3. Fix: restart Cassandra on the DN nodes (`systemctl start cassandra`) and let them rejoin (state returns to "UN"). If a node is permanently gone, `nodetool removenode <host-id>` then repair.
4. Recheck `nodetool status` (all "UN") and re-run the query — when it meets consistency, conclude (submit) immediately.

match: cockroach, cockroachdb, cockroachdb/cockroach
signature: replica unavailable, unable to serve request to, have been waiting
push: rescue
status: candidate
# CockroachDB range unavailable — fix procedure
1. "replica unavailable ... unable to serve request to r<N>" / "have been waiting ... for slow proposal" = a range lost quorum: a majority of its replicas are on down nodes.
2. Find the down nodes: `cockroach node status --ranges --insecure` (or --certs-dir=...). Rows with is_live=false / is_available=false are the cause.
3. Fix: restart the dead cockroach nodes (or heal the network partition) so a majority is live again — most ranges self-recover once quorum returns.
4. Recheck: `cockroach node status` shows all nodes live and `SELECT sum(unavailable_ranges) FROM crdb_internal.kv_store_status` is 0 — when ranges recover, conclude (submit) immediately.

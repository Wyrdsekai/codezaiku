match: tidb, tikv, pd, pingcap
signature: region is unavailable, tikv server timeout, 9005
push: rescue
status: candidate
# TiDB region unavailable — fix procedure
1. "[tikv:9005]Region is unavailable" / "[tikv:9002]TiKV server timeout" = a Region lost enough replicas to be unreadable, usually because a TiKV store is down or overloaded.
2. Check stores: `tiup ctl:v<ver> pd -u http://<pd>:2379 store` — look for store state "Down" or "Disconnected"; also check TiKV load in Grafana.
3. Fix: restart the down TiKV nodes (or relieve the load) so replicas rejoin and Regions regain quorum. Confirm with `pd-ctl store` that stores return to "Up".
4. Recheck: re-run a query on the affected table — when it returns without 9005/9002, conclude (submit) immediately.

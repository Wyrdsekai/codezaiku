match: consul, hashicorp/consul
signature: no cluster leader, failed to retrieve raft configuration, raft: no leader
push: rescue
status: candidate
# Consul no cluster leader — fix procedure
1. "No cluster leader" / "raft: no leader" = the Raft peer set lost quorum (too few live servers), so writes and leader-dependent reads fail.
2. Check members: `consul operator raft list-peers` (may return 500) and `consul members` — count live servers vs the expected peer set; a majority is required.
3. Fix: restart the down Consul SERVER agents so quorum returns. If servers are permanently lost, put a `raft/peers.json` (each survivor's id + address, `"non_voter":false`) in each server's data-dir and restart — Consul rebuilds the peer set and elects a leader.
4. Recheck `consul operator raft list-peers` shows a Leader — when one is elected, conclude (submit) immediately.

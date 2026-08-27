match: nats
signature: slow consumer detected, writedeadline, slow_consumers
push: rescue
status: candidate
# NATS slow consumer detected — fix procedure
1. "Slow Consumer Detected" / "WriteDeadline of 2s exceeded" = server buffered faster than a client drains, so it drops messages or closes the connection.
2. Confirm: `curl -s localhost:8222/connz?subs=1 | jq '.connections[]|{cid,pending_bytes}'` — find the high pending_bytes cid; `curl -s localhost:8222/varz | jq .slow_consumers`.
3. Fix: speed up the slow client's handler or add worker concurrency; raise its client buffer via SetPendingLimits (msgs+bytes) to absorb bursts; or raise `write_deadline` in the server config and `nats-server --signal reload`.
4. Recheck varz slow_consumers stops climbing and the cid pending_bytes drains, then conclude (submit) immediately.

# Runbook: an app fails because a dependency is unreachable

Symptom: an app is up but returns 500s, or exits on startup, logging that it cannot reach a backend
(database, cache, message queue, socket). The root is the dependency — but first decide WHICH kind.

## Workflow
1. Read the app's log for the dependency it names and the address (host:port), e.g.
   `could not connect to database 127.0.0.1:5432` / `cache unreachable :6379`.
2. Probe that address: `ss -ltnp | grep :<port>` (is anything listening?), or a connect test.
3. Decide the kind of dependency:
   - **A LOCAL service on this box** (its own process/unit): it is down for a reason — read ITS log
     (`journalctl -u <unit>` or its logfile) and recurse (it may have crashed on disk/config/its own dep).
     Do NOT conclude "dependency down" as the root when you can introspect it — find WHY it is down.
   - **An EXTERNAL service you cannot introspect** (nothing you run on this box): then "the dependency is
     down/unreachable" IS a fair root — confirm nothing on this box (firewall, wrong address, DNS) caused
     it, then conclude `dependency_down` and cite the connect failure.

## Conclude
Either the deeper cause of the local dependency's failure, or `dependency_down` for a confirmed external
outage — with the log line and the port probe as evidence.

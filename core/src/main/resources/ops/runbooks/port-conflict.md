# Runbook: a process cannot bind its port

Symptom: a service fails at startup with "address already in use" / EADDRINUSE, or simply won't come up
on its expected port. The root cause is that ANOTHER process already holds that port.

## Workflow
1. Identify the port the service needs (its config / the incident text).
2. See who holds it: `ss -ltnp | grep :<port>` (or `ss -ltnp 'sport = :<port>'`). Note the PID + program.
3. Confirm the intended service is NOT the holder — the holder is a different/rogue/leftover process.
4. Read the service's own log to confirm it failed BECAUSE of the bind (the fatal "address already in
   use" line) — this ties the conflict to the outage and rules out an unrelated error in the log.
5. Conclude: `port_conflict` — port <N> is held by <program/pid>, so <service> cannot bind. Cite the `ss`
   line and the bind-failure log line.

## Trap
An unrelated stack trace or error already in the log is a common red herring. The bind failure is the
operative cause; a historical exception is not. Verify the TIMESTAMP / that it's the last fatal line.

## Remediation (separate, gated)
Stop/relocate the squatting process (or reconfigure the service's port), then start the service and
confirm it binds and serves.

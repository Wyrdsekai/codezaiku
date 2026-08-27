match: gunicorn
signature: [CRITICAL] WORKER TIMEOUT, WORKER TIMEOUT (pid, Booting worker with pid
push: rescue
status: candidate
platform: systemd
# Gunicorn worker timeout — fix procedure
1. Repeated "[CRITICAL] WORKER TIMEOUT (pid:N)" then a respawn means a request exceeded the master timeout (default 30s) and the worker was killed mid-request — a slow handler or too-low timeout, not a crash.
2. Confirm the pattern: `journalctl -u <svc> | grep "WORKER TIMEOUT"` (or the app log); repeated timeouts on one route point to the slow endpoint.
3. Check whether the work is genuinely slow (DB/external call) or blocking; a sync worker on a long/streaming request times out.
4. Fix: raise `--timeout` (e.g. 120) and/or use threads/async (`--worker-class gthread --threads 4`, or gevent) in the gunicorn command/config, then `systemctl restart <svc>`.
5. Recheck the endpoint completes with no new WORKER TIMEOUT lines; when workers are stable, conclude (submit) immediately.

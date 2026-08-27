match: uwsgi
signature: HARAKIRI ON WORKER, HARAKIRI [core, graceful termination attempt on worker
push: rescue
status: candidate
platform: systemd
# uWSGI harakiri request timeout — fix procedure
1. "*** HARAKIRI ON WORKER N ***" with "HARAKIRI [core ...]" means a request ran longer than the harakiri timeout and uWSGI killed the worker mid-request — a slow handler or too-low harakiri, not a crash.
2. Confirm the pattern and slow route: `journalctl -u <svc> | grep -i harakiri` (or the uWSGI log); the logged URI is the offending endpoint.
3. Check whether the work is genuinely slow (DB/external call) or blocking; the same URI repeating means that handler exceeds the limit.
4. Fix: raise `harakiri` (e.g. harakiri = 120) in the uWSGI ini and/or add `--enable-threads` / more workers, then `systemctl restart <svc>`.
5. Recheck the endpoint completes with no new HARAKIRI lines; when workers are stable, conclude (submit) immediately.

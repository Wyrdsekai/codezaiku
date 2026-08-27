# Runbook: a service is down / won't start

A service being "not running" is almost never the ROOT cause — it is a symptom. Your job is to find the
reason it is down. Do not conclude "the service is stopped, start it" — that fix fails when an underlying
cause (bad config, taken port, dead dependency, exhausted resource) prevents it from starting.

## Workflow
1. Confirm it's down and how: `systemctl status <unit>` (or `ps aux | grep <name>` if there's no systemd).
2. Read WHY it's down — the logs carry the fatal line:
   - `journalctl -u <unit> --no-pager -n 50` (systemd), or the app's own log file.
   - Look for the LAST fatal error before it exited.
3. Validate the config — a daemon that won't load has an invalid config:
   - nginx: `nginx -t`   • sshd: `sshd -t`   • generic: the app's `--check`/`-t` flag.
4. Rule out the usual underlying causes, each with a confirming command:
   - **bad config** → the config test above fails with a specific directive/line.
   - **port conflict** → `ss -ltnp` shows another process already on the port it needs.
   - **dependency down** → it can't reach its DB/cache/socket (`ss`, `curl`, connect test).
   - **resource** → `df -h` (disk full), `free -m` / `dmesg | grep -i oom` (memory).
5. Conclude with the UNDERLYING cause and the exact evidence line that proves it — not "it's stopped."

## Remediation (separate, gated)
Fix the underlying cause FIRST (repair config / free the port / restore the dependency / reclaim
resource), THEN start the service and verify it stays up and serves.

# Runbook: a filesystem is full / writes fail with ENOSPC

Symptom: an app returns errors on writes, a service won't start, or a log shows "No space left on device"
(errno 28). The root is a full filesystem — find WHICH one and what the failing component needs.

## Workflow
1. `df -h` (and `df -h <the path the app writes>`): find the mount at/near 100%. Note the mount point.
2. Tie it to the failure: the failing component's log names the path it couldn't write (e.g.
   `/var/lib/appdata/...: No space left on device`). Confirm that path is on the full mount.
3. Conclude `disk_full` — mount <X> is at 100%, so <component> cannot write and fails. Cite the `df` line
   and the ENOSPC log line.
4. (Optional, for remediation) find what filled it: `du -xh <mount> | sort -h | tail`.

## Trap
A full mount often causes a DOWNSTREAM failure (app crash → 502, service won't start). Do not stop at the
downstream symptom — the `df` at 100% is the root; the crash is its effect.

## Remediation (separate, gated)
Free space (remove the large/stale files or logs), confirm `df` is back under threshold, then restart the
affected component and verify it serves.

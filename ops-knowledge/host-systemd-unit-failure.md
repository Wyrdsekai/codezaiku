match: linux, host, systemd, systemctl, service
signature: start-limit-hit, start request repeated too quickly, failed with result, activating (auto-restart)
push: rescue
status: candidate
platform: systemd
# Host systemd unit restart loop — fix procedure
1. `systemctl status <svc>`: "failed"/"start-limit-hit" or "start request repeated too quickly" = it crash-looped past StartLimitBurst.
2. Read the REAL cause (not the rate limit): `journalctl -u <svc> -n 50 --no-pager` — find the exit reason before the limit hit.
3. Fix that root cause (bad config path, missing dep/env, port in use, permissions).
4. Clear the throttle and restart: `systemctl reset-failed <svc>` then `systemctl start <svc>`.
5. Confirm `systemctl is-active <svc>` = active and it stays up, then conclude (submit) immediately.

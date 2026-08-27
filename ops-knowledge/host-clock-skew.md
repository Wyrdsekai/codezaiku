match: linux, host, ntp, chrony, time
signature: clock skew detected, not synchronised, system clock synchronized: no
push: rescue
status: candidate
platform: systemd
# Host clock skew — fix procedure
1. Symptoms of drift: "clock skew detected", Kerberos "Clock skew too great", or TLS "not yet valid"/auth failures.
2. Check sync state: `timedatectl` (System clock synchronized: no) and `chronyc tracking` (Leap status: Not synchronised; large System time offset).
3. Verify sources reachable: `chronyc sources -v` (or `ntpq -p`).
4. Correct now: `chronyc makestep` to step the clock immediately (or `systemctl restart chronyd`); ensure a reachable NTP server is configured.
5. Re-check `timedatectl` shows synchronized and offset is small, then conclude (submit) immediately.

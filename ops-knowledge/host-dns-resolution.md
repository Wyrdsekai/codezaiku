match: linux, systemd, host, resolv.conf
signature: temporary failure in name resolution, name or service not known, eai_again, server misbehaving
push: rescue
status: candidate
# Host DNS resolution failure — fix procedure
1. "Temporary failure in name resolution"/"Name or service not known" -> the host cannot resolve names.
2. Test: `getent hosts <name>` and `dig +short <name> @1.1.1.1`. If the direct-server query works but plain resolve fails, resolver config is broken.
3. Inspect `cat /etc/resolv.conf` for a missing/wrong `nameserver`. On systemd-resolved: `resolvectl status`.
4. Fix: set a working `nameserver` (restart `systemd-resolved`/`NetworkManager`, or correct the netplan/dhcp source rather than hand-editing a symlinked resolv.conf).
5. Re-run `getent hosts <name>`; when it resolves and the app reconnects, conclude (submit) immediately.

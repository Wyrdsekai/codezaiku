match: linux, host, disk, df, filesystem
signature: no space left on device, enospc
push: rescue
status: candidate
platform: docker, systemd
# Host disk full (bytes or inodes) — fix procedure
1. `df -h`: a mount at 100% Use% (writes fail with ENOSPC) is the culprit; note it as `<mnt>`.
2. If df -h shows space FREE but writes still fail ENOSPC, it is INODES not bytes: `df -i`; a mount at 100% IUse% is out of inodes — clear its swarm of tiny files (old cache/session/spool) with `find <mnt> -xdev -type f -mtime +N -delete`, then recheck `df -i`.
3. For bytes: find the hogs `du -xhd1 <mnt> 2>/dev/null | sort -rh | head`; check deleted-but-open files `lsof +L1 <mnt>` (restart that service to release them).
4. Reclaim: truncate/rotate large logs (`truncate -s0 <biglog>` or `journalctl --vacuum-size=200M`), clear caches, `apt-get clean`/`docker system prune -f` if present.
5. Re-run `df -h`/`df -i`; when usage drops and writes succeed, conclude (submit) immediately.

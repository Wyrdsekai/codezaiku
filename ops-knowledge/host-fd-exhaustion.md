match: linux, host, process, ulimit, socket
signature: too many open files, emfile, accept4, socket: too many open files
push: rescue
status: candidate
platform: systemd
# Host file-descriptor exhaustion — fix procedure
1. "Too many open files"/EMFILE in a service log -> that process hit its FD limit.
2. Find the PID and its limit: `pidof <svc>`; `cat /proc/<pid>/limits | grep 'open files'`.
3. Count open FDs: `ls /proc/<pid>/fd | wc -l` — near the soft limit confirms exhaustion; `lsof -p <pid> | awk '{print $9}' | sort | uniq -c | sort -rn | head` shows the leak (sockets/files).
4. Restore now: `systemctl restart <svc>`. Raise the ceiling via drop-in `LimitNOFILE=65535` (`systemctl daemon-reload` + restart) or fix the fd/socket leak.
5. When the service accepts connections again, conclude (submit) immediately.

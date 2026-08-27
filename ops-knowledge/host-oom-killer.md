match: linux, host, kernel, dmesg, memory
signature: out of memory: killed process, invoked oom-killer, oom_reaper, oom_score
push: rescue
status: candidate
platform: systemd
# Host OOM killer — fix procedure
1. Confirm: `dmesg -T | grep -iE 'oom-killer|Out of memory: Killed'` names the killed PID/process and time.
2. Check pressure: `free -m` (low available + swap exhausted) and `journalctl -k | tail`.
3. Identify the hog: `ps -eo pid,rss,comm --sort=-rss | head` — top RSS is the offender.
4. Restore service: restart the killed unit (`systemctl restart <svc>`); cap the offender's memory (systemd `MemoryMax=`, app heap/worker count) so it cannot recur.
5. Add swap only if truly under-provisioned. When the service is back up and memory has headroom, conclude (submit) immediately.

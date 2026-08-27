# Runbook: out-of-memory / a process was killed

Symptom: a process disappeared, a service restarts repeatedly, or a log shows "Killed" / "Cannot allocate
memory". The kernel OOM-killer may have terminated a process to reclaim RAM.

## Workflow
1. Check memory pressure: `free -m` (little available? swap exhausted?).
2. Look for OOM kills: `dmesg | grep -iE 'killed process|out of memory|oom-killer'` and
   `journalctl -k | grep -i oom`. These name the victim PID/process.
3. Identify the memory hog: `ps aux --sort=-%mem | head`.
4. Conclude `oom` — the kernel killed <process> under memory pressure; cite the dmesg line + the RSS of the
   hog. (Note: inside a container the kernel OOM log may live on the HOST, not visible here.)

## Trap
A process that "isn't running" may have been OOM-killed rather than crashed on its own — the dmesg line
distinguishes them. Do not blame the app's code if the kernel killed it for memory.

## Remediation (separate, gated)
Reduce the hog's footprint / add a memory limit / add swap, then restart the victim and confirm it stays up.

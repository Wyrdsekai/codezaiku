match: exim, exim4, smtp, mainlog
signature: retry time not reached, retry time not reached for any host, frozen
push: rescue
status: candidate
# Exim frozen / retry-deferred queue — fix procedure
1. Read /var/log/exim/mainlog: "retry time not reached for any host" (deferred, waiting on retry interval) or "*** frozen ***" messages stuck in queue.
2. Inspect the queue: exim -bp (list) and exim -Mvl <msgid> (why deferred — DNS/MX failure, connection refused, or over-quota).
3. Fix the root cause: resolve MX/DNS, restore the remote route, or delist the IP; do not just clear the queue over an unfixed cause.
4. Force delivery ignoring the retry timer: exim -qf (whole queue) or exim -M <msgid> (one); thaw with exim -Mt <msgid>.
5. Recheck mainlog — when messages log "=> " completed deliveries and the queue drains, conclude (submit) immediately.

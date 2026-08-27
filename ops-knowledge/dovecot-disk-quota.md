match: dovecot, imap, lmtp, maildir
signature: quota exceeded (mailbox for user is full), disk quota exceeded, 552 5.2.2
push: rescue
status: candidate
# Dovecot mailbox over-quota delivery failure — fix procedure
1. Read the log: "Quota exceeded (mailbox for user is full)" / "552 5.2.2" (Dovecot quota) or "Disk quota exceeded" (underlying filesystem quota) blocks LMTP delivery.
2. Distinguish which: check filesystem with `df -h` on the mail store, and the user with `doveadm quota get -u <user>`.
3. Fix filesystem-full: free space or grow the volume so writes and lock files succeed.
4. Fix mailbox quota: raise the rule (`doveadm quota recalc -u <user>` after adjusting the quota rule) or have the user delete mail with `doveadm expunge`.
5. Recheck — when a test delivery lands and the quota/Disk-quota errors stop, conclude (submit) immediately.

match: samba, smb, smbclient, cifs
signature: nt_status_access_denied, tree connect failed, nt_status_logon_failure
push: rescue
status: candidate
platform: systemd
# Samba share access denied — fix procedure
1. Read the error: "tree connect failed: NT_STATUS_ACCESS_DENIED" (share ACL/permission block) or "NT_STATUS_LOGON_FAILURE" (bad credentials).
2. Confirm the share and user: testparm -s to see the [share] valid users / path; smbclient -L //<host> -U <user> to test auth.
3. Fix credentials: ensure the Samba account exists with `smbpasswd -a <user>` (Unix account too); enabled via `smbpasswd -e <user>`.
4. Fix ACL: set the share's `valid users`/`write list` in smb.conf and the underlying dir perms/ownership; keep `force user` consistent.
5. Reload: smbcontrol all reload-config (or systemctl reload smbd).
6. Recheck — when smbclient connects and lists the share without NT_STATUS_*, conclude (submit) immediately.

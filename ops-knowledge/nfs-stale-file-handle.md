match: nfs, mount, nfsd, autofs
signature: stale file handle, nfs server not responding, estale
push: rescue
status: candidate
# NFS stale file handle — fix procedure
1. Read the error: "Stale file handle" (ESTALE) means the client references an export whose inode/fsid changed on the server (recreated dir, re-exported, or failover).
2. Confirm from the client: `ls /mnt/<nfs>` returns the error; `dmesg | grep -i nfs` and `showmount -e <server>` to check the export still exists.
3. Fix by remounting cleanly: umount /mnt/<nfs> (or `umount -l` if busy), then `mount -a`.
4. If the export itself changed on the server: re-run `exportfs -ra` there, then remount the client.
5. Recheck — when `ls`/reads on the mount succeed and the Stale-file-handle error is gone, conclude (submit) immediately.

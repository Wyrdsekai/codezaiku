match: minio, s3, mc, minio-server
signature: xminiostoragefull, storage backend has reached its minimum free disk threshold, please delete a few objects to proceed
push: rescue
status: candidate
# MinIO storage backend full — fix procedure
1. Read the error: "XMinioStorageFull" / "Storage backend has reached its minimum free disk threshold. Please delete a few objects to proceed." — writes rejected.
2. Confirm on the backend host: `df -h <data-dir>` for space AND `df -i <data-dir>` for inodes (either exhausted triggers this).
3. Fix: free space on the data volume (rotate logs, remove non-MinIO cruft) or grow the disk; the guard clears once free space passes the threshold.
4. Or reclaim within the store: `mc rm --recursive --force <alias>/<bucket>/<oldprefix>` and expire old versions via a lifecycle rule.
5. Recheck — when `mc admin info <alias>` shows drives online and a test PUT succeeds, conclude (submit) immediately.

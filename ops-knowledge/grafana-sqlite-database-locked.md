match: grafana
signature: database is locked, sqlstore.max-retries-reached, database locked, sleeping then retrying
push: rescue
status: candidate
# Grafana SQLite database is locked — fix procedure
1. "database is locked" / "[sqlstore.max-retries-reached]" = concurrent writes to the single-file SQLite DB are serializing and timing out.
2. Confirm: grep the log for "database is locked"; check `grafana.ini` [database] type=sqlite3 and the grafana.db path; `df -h` and `ls -l` that path (disk full / bad perms make it worse).
3. Fix: enable WAL — set `[database] wal = true` and restart Grafana (biggest concurrency win); ensure the DB volume has space and grafana owns the file; for sustained load migrate to MySQL/Postgres via [database] type/host.
4. Recheck the log stops emitting "database is locked" and the UI saves, then conclude (submit) immediately.

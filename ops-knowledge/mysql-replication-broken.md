match: mysql, mariadb, percona
signature: slave sql for channel, coordinator stopped because there were error(s), slave sql thread
push: rescue
status: candidate
# MySQL replica SQL thread stopped — fix procedure
1. Error log "Slave SQL for channel ... failed executing transaction" / "coordinator stopped because there were error(s)" = the replica SQL thread aborted (often a duplicate-key). `SHOW REPLICA STATUS\G` shows Replica_SQL_Running: No + Last_SQL_Error.
2. Read Last_SQL_Error to confirm the event is safely skippable.
3. GTID fix: `STOP REPLICA; SET GTID_NEXT='<uuid:N>'; BEGIN; COMMIT; SET GTID_NEXT='AUTOMATIC'; START REPLICA;`. Non-GTID: `SET GLOBAL SQL_SLAVE_SKIP_COUNTER=1; START REPLICA;`.
4. Recheck `SHOW REPLICA STATUS\G` — when Replica_SQL_Running: Yes and Seconds_Behind_Source falls, conclude (submit) immediately.

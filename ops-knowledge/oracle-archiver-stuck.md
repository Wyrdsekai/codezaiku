match: oracle, oracledb, oracle-database, container-registry.oracle.com/database
signature: ora-00257, archiver error, connect internal only, until freed
push: rescue
status: candidate
# Oracle ORA-00257 archiver stuck — fix procedure
1. "ORA-00257: archiver error. Connect internal only, until freed" = the flash recovery area (db_recovery_file_dest) is full, so ARCn cannot write archivelogs and Oracle blocks new connections.
2. Connect as SYSDBA (still allowed): `sqlplus / as sysdba`. Confirm: `SELECT * FROM v$recovery_file_dest;` — SPACE_USED is at SPACE_LIMIT.
3. Reclaim with RMAN: `rman target /` then `CROSSCHECK ARCHIVELOG ALL; DELETE NOPROMPT ARCHIVELOG UNTIL TIME 'SYSDATE-1' BACKUP 1 TIMES;` — or raise db_recovery_file_dest_size if disk has room.
4. Recheck v$recovery_file_dest and reconnect as a normal user — when connections succeed, conclude (submit) immediately.

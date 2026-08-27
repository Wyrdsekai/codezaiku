match: sql server, mssql, sqlserver, mcr.microsoft.com/mssql/server
signature: has been marked suspect by recovery, error: 926, cannot be opened. it has been marked suspect
push: rescue
status: candidate
# SQL Server database SUSPECT — fix procedure
1. "Error: 926 ... Database 'DB' cannot be opened. It has been marked SUSPECT by recovery" = crash recovery failed, usually a lost/corrupt LDF or an I/O error. Read the ERRORLOG for the underlying file/IO error first.
2. If a good backup exists, RESTORE it — that is the safe path.
3. Otherwise repair in place: `ALTER DATABASE DB SET EMERGENCY; ALTER DATABASE DB SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DBCC CHECKDB (DB, REPAIR_ALLOW_DATA_LOSS); ALTER DATABASE DB SET MULTI_USER;`
4. Confirm online: `SELECT state_desc FROM sys.databases WHERE name='DB';` returns ONLINE — when it does, conclude (submit) immediately.

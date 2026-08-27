match: oracle, oracledb, oracle-database, container-registry.oracle.com/database
signature: ora-12541, tns:no listener, tns-12541
push: rescue
status: candidate
# Oracle ORA-12541 no listener — fix procedure
1. "ORA-12541: TNS:no listener" = the TNS listener process is not running (or not on the expected host/port), so clients cannot connect.
2. Check it: `lsnrctl status`. Connection refused / "No listener" confirms it is down.
3. Start it: `lsnrctl start`. Then confirm the instance registered its service — `lsnrctl status` should list the service handler (PMON registers within ~60s; force with `ALTER SYSTEM REGISTER;` as sysdba).
4. Verify end-to-end: `tnsping <service>` then a client connect — when it connects, conclude (submit) immediately.

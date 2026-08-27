match: mysql, mariadb
signature: too many connections, er_con_count_error, 1040
push: rescue
status: candidate
# MySQL/MariaDB connection exhaustion — fix procedure
1. "ERROR 1040 (HY000): Too many connections" means max_connections is saturated — usually a client-side
   connection leak, not real demand.
2. See the holders: `mysql -e "SHOW PROCESSLIST"` (or select from information_schema.processlist) — many
   "Sleep" threads from one host is the leak.
3. Reclaim now: `mysql -e "KILL <id>"` for the oldest sleeping threads; or lower the client pool's idle
   timeout so it stops hoarding.
4. If load is real, raise it live: `mysql -e "SET GLOBAL max_connections=<n>"` (and persist in my.cnf).
   Recheck SHOW PROCESSLIST — when the app reconnects and errors stop, conclude (submit) immediately.

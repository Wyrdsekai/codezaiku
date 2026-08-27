match: postgres, postgresql, psql
signature: too many clients already, remaining connection slots are reserved, sorry, too many clients
push: rescue
status: candidate
# PostgreSQL connection exhaustion — fix procedure
1. The app error "FATAL: sorry, too many clients already" means max_connections is saturated — usually a
   connection LEAK, not real load.
2. See who holds them: `psql -c "select state, count(*) from pg_stat_activity group by state"`. A large
   "idle in transaction" count is the leak's fingerprint.
3. Reclaim now: terminate the stuck sessions —
   `psql -c "select pg_terminate_backend(pid) from pg_stat_activity where state='idle in transaction' and state_change < now()-interval '5 min'"`.
4. If load is genuinely high, raise `max_connections` (needs restart) or front the db with a pooler
   (pgbouncer). Recheck pg_stat_activity — when connections drain and the app reconnects, conclude (submit).

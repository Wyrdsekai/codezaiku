match: postgres, postgresql
signature: cannot execute insert in a read-only transaction, read-only transaction, default_transaction_read_only, cannot execute update in a read-only
push: rescue
status: candidate
# Postgres stuck in read-only — writes failing — fix procedure
1. Writes fail "cannot execute INSERT/UPDATE in a read-only transaction" while reads work — the database
   defaults new transactions to read-only (`default_transaction_read_only=on`), e.g. left on after a failover.
2. Confirm: `SHOW default_transaction_read_only;` — `on` is the fault.
3. A plain `ALTER DATABASE ... off` FAILS (your session is read-only too) and a RESTART does NOT help (the
   setting persists in the catalog). Run it in ONE psql call with TWO -c flags so the read-write session
   carries to the ALTER:
   `psql -U <user> -d <db> -c "SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE" -c "ALTER DATABASE <db> SET default_transaction_read_only=off"`
4. New connections are writable; confirm an INSERT succeeds, then conclude.

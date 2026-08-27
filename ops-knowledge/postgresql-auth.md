match: postgres, postgresql, psql
signature: password authentication failed, no pg_hba.conf entry, role does not exist, does not exist
push: rescue
status: candidate
# PostgreSQL authentication fault — fix procedure
1. Read the exact FATAL from the app/db log: "password authentication failed" (wrong secret),
   "no pg_hba.conf entry for host" (host not allowed), or "role ... does not exist" (missing user).
2. Confirm the app's expected user/db/password from its deployment env or secret.
3. Fix the matching cause as the superuser:
   - missing role → `CREATE ROLE <u> LOGIN PASSWORD '<p>'`; wrong password → `ALTER ROLE <u> PASSWORD '<p>'`.
   - no pg_hba entry → add a line for the app's host/db/user to pg_hba.conf, then `SELECT pg_reload_conf()`.
4. Recheck the app log once — when the FATALs stop, conclude (submit) immediately.

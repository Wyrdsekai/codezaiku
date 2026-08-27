match: sql server, mssql, sqlserver, mcr.microsoft.com/mssql/server
signature: login failed for user, error: 18456, password did not match that for the login provided
push: rescue
status: candidate
# SQL Server login failed (18456) — fix procedure
1. "Login failed for user ... Error: 18456, Severity: 14, State: N" is an auth failure; the State names the cause: 8 = wrong password ("Password did not match that for the login provided"), 5 = login missing, 38 = default DB unavailable.
2. Read the ERRORLOG line to get the State number.
3. Fix State 8 (SQL auth): `ALTER LOGIN [app] WITH PASSWORD='<newpw>';` and update the app connection string. If SQL auth is disabled, enable Mixed Mode and restart the instance.
4. Reconnect: `sqlcmd -S localhost -U app -P '<newpw>' -Q "SELECT 1"` — when it authenticates, conclude (submit) immediately.

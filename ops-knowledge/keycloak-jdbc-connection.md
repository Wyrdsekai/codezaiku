match: keycloak, oidc, quarkus, keycloak-db
signature: failed to obtain jdbc connection, unable to obtain isolated jdbc connection
push: rescue
status: candidate
platform: docker, systemd
# Keycloak database connection failure at startup — fix procedure
1. Read the boot error: "Failed to obtain JDBC connection" / "unable to obtain isolated JDBC connection" means Keycloak cannot reach its DB.
2. Confirm the DB target from env: KC_DB, KC_DB_URL (host/port), KC_DB_USERNAME, KC_DB_PASSWORD.
3. Verify the DB is up and reachable: pg_isready -h <host> -p <port> (or nc -vz <host> <port>); check for "Connection refused".
4. Fix the cause: start the DB (systemctl start postgresql / docker start <db>), correct KC_DB_URL host:port, or fix credentials, then restart Keycloak.
5. Recheck the Keycloak log — when it reaches "Keycloak ... started" with no JDBC error, conclude (submit) immediately.

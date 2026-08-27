match: redis, valkey
signature: --requirepass
push: rescue
status: validated
platform: docker
reuses: 5
# Redis/Valkey password set in the SERVER'S LAUNCH ARGS — fix procedure
1. `{{.Config.Cmd}}` carries `--requirepass`, so the command line is the source of truth and
   `CONFIG SET requirepass ''` only holds until the next restart.
2. If the value comes from a compose variable, recreate that ONE service with the variable overridden
   in the invoking shell — a shell value beats the project's .env, and it edits no file:
   `CACHE_PASSWORD= docker compose -p <project> up -d --force-recreate <service>`
   Use the real variable name from the Cmd, and the real project/service names.
3. Confirm `docker inspect <container> --format '{{.Config.Cmd}}'` no longer shows `--requirepass`
   and the client's auth errors stop.
4. Check it survived: `docker restart <container>`, wait, confirm the client is still healthy, then
   conclude (submit).

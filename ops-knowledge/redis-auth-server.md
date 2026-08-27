match: redis, valkey
signature: wasn't able to connect to redis, noauth authentication required, authentication required, wrongpass, invalid password, invalid username-password pair
push: immediate
status: validated
platform: kubernetes, docker
# Redis/Valkey server auth (requirepass) mismatch — fix procedure
1. A client that "can't connect to redis" while the redis/valkey PROCESS is Running usually means the
   SERVER's requirepass was changed and clients no longer have it — look at the SERVER, not the client.
2. Confirm: `kubectl exec <valkey-pod> -n <ns> -- valkey-cli CONFIG GET requirepass` (add `-a <pass>` if it
   replies NOAUTH). A non-empty value the client does not share is the fault.
3. Reset it to what the client expects (usually empty): `valkey-cli -a <current> CONFIG SET requirepass ''`
   via kubectl exec. If requirepass is not persisted in a config file/args, just RESTART the server pod —
   the in-memory value clears to default.
4. Restart the client so it reconnects; when its redis errors stop and it is Ready, conclude (submit).

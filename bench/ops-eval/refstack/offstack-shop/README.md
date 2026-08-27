# offstack-shop — off-substrate generality fixture

A deliberately MINIMAL, genuinely-different compose stack (NOT refstack) to prove `fix` is not
refstack-hardcoded. Project `shop`: `web` (a tiny stdlib http /health that write-probes its cache)
+ `cache` (redis:7-alpine), on port 38080.

Bring up:  `cd offstack-shop && docker compose up -d`
Trigger:   `FamiliarMain fix shop guarded`   (no CODEZAIKU_OPS_APP_HEALTH/VERIFY/STACK env)

Proven: `fix shop guarded` on an injected redis-auth (`docker exec shop-cache-1 redis-cli CONFIG
SET requirepass badpass123`) → auto-discovered :38080/health → localized `cache` → the redis card
did NOT match (service is named `cache`, not `redis`) → RECON derived + guarded-remediation applied
the runtime fix → verified=true, harm=none, cache PONG. Same product code, different stack.

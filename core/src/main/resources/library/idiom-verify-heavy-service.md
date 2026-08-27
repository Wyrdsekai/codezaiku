# Verifying a service that loads a heavy model — boot ONCE, poll the port, don't short-sleep

A service that loads a model is SLOW to become ready: `sentence-transformers` ~10s; embedding a whole
corpus at first boot takes **minutes**. The mistake that burns entire runs: re-import the app or re-boot
the service on every test, each one reloading the model and timing out — so you "fix" code that was
never broken and thrash.

Do this instead — boot it **ONCE** in the background, **POLL the port until it answers** (allow up to
~120s on first build), THEN query it as many times as you need:

       (python app.py &)                                       # one boot, in the background
       for i in $(seq 1 120); do curl -sf localhost:8080/health && break; sleep 1; done
       curl "localhost:8080/search?q=immune%20response&k=5"     # now query repeatedly — model is loaded

Rules:
- **Never a fixed short sleep** (`sleep 5`) before the first request — the model isn't loaded yet, the
  request fails, and you'll misread it as a bug.
- Give model-loading shell commands a **generous timeout** (≥180s), never `timeout 3`.
- If you **persist** the index/model (separate idiom), the SECOND boot is instant — prefer verifying
  against the warm second boot.

Litmus: the service answers a real query over HTTP on a single long-lived boot; you did not need to
restart it to "retry".

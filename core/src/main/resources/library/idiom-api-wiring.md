# Wiring a multi-concern pipeline to its web interface (endpoints + dashboard)

A data pipeline is NOT done until each concern is reachable over HTTP and the dashboard renders it. The
common gap: the pipeline functions exist but nothing exposes them, so `/api/...` 404s and the dashboard
is empty or 500s on real input. Boot-on-empty-data hides this; it surfaces the moment real data flows.

Expose ONE endpoint per concern, each returning the pipeline's COMPUTED data as JSON:

    GET /api/threads      -> [ {thread_id, message_count, participants, first, last}, ... ]
    GET /api/bills        -> [ {sender, amount, currency, date, category}, ... ]  (+ totals)
    GET /api/categories   -> [ {name, count}, ... ]
    GET /api/predictions  -> { next_sender, ... }

Use the exact route names the spec lists; if it just says "endpoints exposing threads, categories, bills,
predictions", `/api/<concern>` is the conventional shape. Register every one of them — a concern with a
function but no route is an unmet requirement.

The dashboard route (`GET /` or `/dashboard`) builds the page from those SAME computed values — call the
pipeline (or your own endpoints) and render REAL rows (a category name, a bill amount), not a static
shell. A page that renders nothing — or an app that only returns JSON when the spec asks for a dashboard —
does not satisfy the spec.

Wire it to run on REAL input, not an assumed-empty store: the pipeline must execute over the loaded data
WHEN THE ROUTE IS HIT. Handle BOTH ends — no data → empty list (not a crash); and a real corpus (a
19-message mbox with reply chains and multi-currency bills) → must NOT 500. The frequent crash causes:
calling a method on the wrong type (a dict vs your model object), indexing a key your record never set,
assuming every message has every header. Read the actual shape before you index it.

Prove it end-to-end with ONE integration test that BOOTS the app against `./fixtures/sample.mbox` and
asserts: `GET /api/threads` → 200 and a non-empty array; `GET /` → 200 and the HTML contains a real
computed value. If `GET /` 500s on the sample data, the pipeline isn't wired — fix that before done.

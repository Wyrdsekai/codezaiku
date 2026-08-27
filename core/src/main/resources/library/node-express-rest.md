# Node · Express · CommonJS REST — worked example (adapt names; do NOT copy verbatim)

CommonJS ONLY: every `.js` file uses `require()` / `module.exports`. Do NOT use `import`/`export`,
and do NOT put `"type":"module"` in package.json (the runtime is plain `node`, no compile step).
There is exactly ONE server entry point that calls `.listen()` — never two.

Entry point (e.g. `src/server.js`) — the ONLY file that calls `.listen()`:

    const app = require("./app");
    const port = process.env.PORT || 8090;
    app.listen(port, () => console.log(`listening on ${port}`));

App wiring (`src/app.js`) — builds the express app, mounts routers, EXPORTS it (does not listen):

    const express = require("express");
    const app = express();
    app.use(express.json());
    app.use("/api/things", require("./routes/things"));
    module.exports = app;

A router (`src/routes/things.js`):

    const express = require("express");
    const router = express.Router();
    const store = [];
    router.get("/", (req, res) => res.json(store));                 // 200 + JSON array
    router.post("/", (req, res) => {                                // 201 + created object with id
        const t = { id: store.length + 1, name: req.body.name };
        store.push(t);
        res.status(201).json(t);
    });
    router.get("/:id", (req, res) => {                              // 200 or 404
        const t = store.find(x => x.id === Number(req.params.id));
        return t ? res.json(t) : res.sendStatus(404);
    });
    router.delete("/:id", (req, res) => res.sendStatus(204));       // 204
    module.exports = router;

`package.json` has `"scripts": { "start": "node src/server.js" }`, an `express` dependency, and
**no `"type"` field**. Install deps with `npm install express` (network is on).

Tests use the built-in runner (`"scripts": { "test": "node --test test/" }`) — exercise the real
app in-process and assert EXACT values; close the server in `after()` or the test process never
exits (a hung `npm test` is a failing test):

    const { test, after } = require("node:test");
    const assert = require("node:assert/strict");
    const app = require("../src/app");

    const server = app.listen(0);                        // ephemeral port — no conflicts
    const base = () => `http://127.0.0.1:${server.address().port}`;
    after(() => server.close());                         // REQUIRED or node --test hangs

    test("POST creates and GET returns it", async () => {
        const res = await fetch(`${base()}/api/things`, {
            method: "POST", headers: { "content-type": "application/json" },
            body: JSON.stringify({ name: "x" }) });
        assert.equal(res.status, 201);
        assert.equal((await res.json()).name, "x");      // exact values, never just non-null
        const list = await (await fetch(`${base()}/api/things`)).json();
        assert.equal(list.length, 1);
    });

A test that only CALLS a function, or asserts nothing, goes green on a hollow implementation —
every test asserts counts and field values the real code computed.

If the goal asks for a DASHBOARD page (HTML, not JSON), a JSON API alone does NOT satisfy it — you
must serve an actual HTML page at a route. Minimal, no template engine needed:

    app.get("/", (req, res) => {                         // or "/dashboard"
        const cats = store.categoriesWithCounts();        // call the REAL pipeline, not placeholders
        const rows = cats.map(c => `<tr><td>${c.name}</td><td>${c.count}</td></tr>`).join("");
        res.type("html").send(`<!doctype html><html><head><title>Dashboard</title></head>
          <body><h1>Dashboard</h1><table>${rows}</table></body></html>`);
    });

The page MUST render real computed data (a bill amount, a category count), not a hardcoded shell.
Prove it with a test: `GET /` returns 200, content-type text/html, and the body contains a real
value (`assert.match(body, /utilities/)`). A dashboard that 404s or returns JSON is an unmet concern.

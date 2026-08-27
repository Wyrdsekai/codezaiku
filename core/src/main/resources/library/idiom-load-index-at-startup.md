# Serve a built index/model: LOAD it at startup — the bare server entry must work

If your service builds an index or trains a model, the running server must **load that artifact when it
starts** — not only inside your tests. The bug that passes your tests but 500s when served: defining a
`load_index()`/build function but **never calling it on startup**, so the global stays `None` and the first
real request crashes (`'NoneType' has no attribute 'search'`).

Wire the load at import / startup so the **bare** `uvicorn module:app` entry works:

    # module level (runs on import — simplest, works with `uvicorn app:app`):
    model = SentenceTransformer("all-MiniLM-L6-v2")
    index = faiss.read_index(os.path.join(os.path.dirname(__file__), "index.faiss"))
    with open(os.path.join(os.path.dirname(__file__), "ids.json")) as f:
        ids = json.load(f)

    # or a FastAPI startup hook:
    @app.on_event("startup")
    def _load():
        global index, ids
        index = faiss.read_index("index.faiss"); ...

Use paths relative to the file (`os.path.dirname(__file__)`) so it works regardless of the working directory.

Litmus: boot the service **the way it is actually run** — `uvicorn module:app` (NOT your test harness) — wait
for the port, and `curl` one real request. If that 500s with a `None`/"not loaded" error, you forgot to call
the load at startup. Tests that call `load_index()` themselves do NOT prove the served app loads it.

# Persist a built model/index — use the format's native writer, load-or-build at startup

Re-building an index or re-embedding a corpus on every startup is slow (minutes). Build it ONCE, write
it to a file, and LOAD that file on startup. **Use each artifact's native writer — never `json.dump`:**

- faiss index → `faiss.write_index(index, "index.faiss")` / `faiss.read_index("index.faiss")`
- numpy array → `np.save("emb.npy", emb)` / `np.load("emb.npy")`
- sklearn / xgboost model → `joblib.dump(model, "model.joblib")` / `joblib.load(...)`

Save the id-list / metadata **alongside** (e.g. `ids.json` with the row→`_id` mapping) so you can map
index rows back to real ids after loading.

Load-or-build at startup:

       if Path("index.faiss").exists():
           index = faiss.read_index("index.faiss")
       else:
           index = build_index(...)            # embed + add vectors
           faiss.write_index(index, "index.faiss")

**Trap that silently corrupts the artifact:** `json.dump()` on numpy embeddings raises
`TypeError: Object of type float32 is not JSON serializable` and leaves a truncated/empty file → the
service loads an empty index and returns nothing. Use the native writer above, not JSON.

Litmus: after building, the artifact file is non-trivial in size (a real index/embedding matrix is
KB–MB, not a few bytes), and a fresh process that only `read`s it (no rebuild) serves real results.

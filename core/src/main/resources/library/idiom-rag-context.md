# RAG /chat service — ONE complete reference (copy it whole; adapt only the marked TASK-SPECIFIC parts)

A RAG service is a multi-link pipeline (ingest → index → retrieve → generate → cite) and it fails a DIFFERENT
link each time you rebuild it by hand: index re-built per request, `citations` returned empty, the LLM fed doc
**ids/placeholders** instead of the real **text**, or a verbose answer that scores ~0 token-F1. Don't reassemble
it — copy this complete service and change only the two lines marked TASK-SPECIFIC (the corpus field names and
the port, both named in your spec). It builds the index ONCE at startup, retrieves with real embeddings, grounds
the LLM on the retrieved TEXT, and returns the REAL retrieved ids.

The normalized embedding matrix `mat` below **IS** the vector index your spec means by "a vector index / semantic
search" — for a corpus of a few thousand documents this in-memory cosine search is exact, fast, and complete, so
`retrieve()` is finished exactly as written. Copy it verbatim and call it: the matrix is the index, and returning
the doc objects keeps each `_id` bound to its own text.

    import os, json, numpy as np, requests
    from fastapi import FastAPI
    from pydantic import BaseModel
    from sentence_transformers import SentenceTransformer

    # --- INGEST + INDEX once, at import/startup (NOT per request) ---
    CORPUS, PORT = "data/corpus.jsonl", 8080          # TASK-SPECIFIC #1: paths/port/field names from your spec
    docs = [json.loads(l) for l in open(CORPUS) if l.strip()]   # the id+text travel TOGETHER inside each dict
    emb  = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")
    mat  = emb.encode([d["text"] for d in docs], normalize_embeddings=True,
                      convert_to_numpy=True, batch_size=64)      # THIS matrix IS the vector index — row i == docs[i]
    assert mat.shape[0] == len(docs)                 # alignment self-check: row i MUST map to docs[i], or retrieval lies

    def retrieve(q, k=3):                             # real embedding retrieval; return the DOC OBJECTS, never bare indices
        qv = emb.encode([q], normalize_embeddings=True, convert_to_numpy=True)[0]
        top = np.argsort(-(mat @ qv))[:k]            # cosine (vectors are normalized) → top-k row indices
        return [docs[i] for i in top]                # i indexes docs the SAME way mat was built → id+text bound, can't desync

    def generate(q, ctx_text):
        ctx = "\n\n".join(f"[{i+1}] {t}" for i, t in enumerate(ctx_text))   # the TEXT, never the ids
        msgs = [{"role": "system", "content": "Answer the question using the context. Give your BEST short answer "
                 "(a word or short phrase), no explanation — extract it from the context even if you are unsure. "
                 "Only if the context is truly unrelated, reply 'unknown'."},   # don't reflexively refuse — it tanks F1
                {"role": "user", "content": f"Context:\n{ctx}\n\nQuestion: {q}\nAnswer:"}]
        r = requests.post(f"{os.environ['LLM_URL']}/v1/chat/completions",      # the PROVIDED LLM (don't run your own)
                          json={"model": "x", "messages": msgs, "temperature": 0, "max_tokens": 64}, timeout=60)
        return r.json()["choices"][0]["message"]["content"].strip()

    app = FastAPI()
    class Q(BaseModel):
        question: str
    @app.post("/chat")
    def chat(q: Q):
        hits = retrieve(q.question)                                          # full doc dicts
        return {"answer":    generate(q.question, [d["text"] for d in hits]),
                "citations": [d["_id"] for d in hits]}                       # TASK-SPECIFIC #2: response schema from
                                                                            # your spec. id AND text come from the SAME
    # run: uvicorn service:app --host 0.0.0.0 --port 8080                    # hits — never a bare index or a wrong id

The four links that each silently tank the score — verify ALL:
1. **Index built ONCE at startup**, not per request (module-level as above) — re-embedding 2000 docs per request
   times out the grader.
2. **`citations` are the REAL retrieved ids** — returning `[]`, invented ids, or the bare numpy INDICES (`540`
   instead of the doc's `_id`) scores citation-accuracy 0. The robust idiom (every vector store — LlamaIndex,
   Chroma — works this way): keep the id and text BOUND inside one object and have `retrieve()` return the OBJECTS,
   so `_id` and `text` always come from the same hit — never carry a separate `ids[]` list and map indices into it
   (that desyncs the moment you re-sort or rebuild a list, and you return the wrong/duplicate id).
3. **The LLM gets the document TEXT, never ids or a `placeholder_text_for_{id}` stub** — ids give it nothing to
   answer from. Finish the id→text grounding.
4. **Answer CONCISELY** — a verbose paragraph scores ~0 token-F1 against a short reference answer even when it is
   "correct". The system prompt above forces a short exact answer.

Litmus: POST a question whose answer is in the corpus → the `answer` contains the expected fact (not "I cannot
tell") AND `citations` lists the gold doc's id. If swapping the retrieved text for ids wouldn't change the answer,
you aren't grounding on the documents. A real reference build (MiniLM top-3 + the provided LLM) scores ~0.71 F1.

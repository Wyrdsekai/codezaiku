# Semantic / embedding search — embed, index, query (real vectors, not keywords)

Rank documents by **embedding similarity**, never keyword/substring/BM25. Three steps:

1. **Embed** the corpus with a sentence-embedding model (`sentence-transformers` `all-MiniLM-L6-v2`),
   embedding `title + " " + text` per doc, in batches, normalized for cosine:

       model = SentenceTransformer("all-MiniLM-L6-v2")
       emb = model.encode(texts, batch_size=128, normalize_embeddings=True)

2. **Index** the vectors with `faiss.IndexFlatIP(dim)` (inner product on normalized vectors == cosine).
   Keep a parallel list `ids` mapping index row → the document's real `_id`.

3. **Query**: embed the query the SAME way and search:

       scores, idx = index.search(model.encode([q]), k)   # encode([q]) is already 2-D — do NOT wrap it again
       return [ids[i] for i in idx[0]]                     # real corpus _ids, ranked

Litmus: two different queries return **different** ranked ids, and returned ids are real corpus `_id`s.
If swapping the model for a fixed list wouldn't fail a test, the test is theater.

(Persist the index so the service starts fast, and verify a heavy-model service correctly — those are
separate idioms pushed alongside this one.)

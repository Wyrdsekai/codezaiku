# TF-IDF text classification — derive categories from the data

Build a working classifier in three concrete steps, using the numbers you compute:

1. **Vectorize** each document with TF-IDF:
   - `tf(t, d)` = count of term `t` in document `d` / number of terms in `d`
   - `df(t)` = number of documents containing `t`;  `N` = total documents
   - `idf(t)` = `log(N / (1 + df(t)))`
   - `weight(t, d)` = `tf(t, d) * idf(t)`  → a document is the sparse vector `{term: weight}`

2. **Derive the categories from the data at runtime** — cluster the TF-IDF vectors (k-means /
   nearest-centroid), or seed centroids from the highest-`idf` (most distinctive) terms, then build one
   representative vector (centroid = mean of member vectors) per discovered cluster.

3. **Classify** each document by **cosine similarity** to each centroid: the predicted category is the
   argmax similarity, and the **confidence is that similarity score (0..1)**. Use the `tf`/`idf` you
   computed — `predict()` must read them, not return a fixed answer.

Litmus before you call it done: feed two clearly different documents and assert they get **different**
categories; feed an empty document and assert it doesn't crash. If replacing `predict()` with
`return SOME_CONSTANT` wouldn't fail any test, the test is theater — make the assertion check a real,
data-derived category.

Avoid the two collapse traps: don't hardcode a fixed category list like `["work", "personal", …]` and
match on the literal word, and don't build `tf`/`idf` in `fit()` then ignore them in `predict()`. Both
collapse to one label for every input.

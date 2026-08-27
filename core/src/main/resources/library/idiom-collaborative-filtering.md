# Collaborative-filtering recommender (item-item cosine, numpy) — ONE complete reference (copy it whole; change only marked lines)

Personalized recommendations from user–item interactions. **Use item-item cosine similarity in plain
numpy** — it is exact, fast enough for thousands of users/items, and needs NO exotic dependency. Do NOT
reach for `implicit`, `lightfm`, or `surprise`: they are heavy C-extension packages that are slow to
COMPILE and frequently fail to build inside a turn budget (a killed `pip install ... implicit -q` fails
SILENTLY, then every run dies on `ModuleNotFoundError` — the classic thrash). numpy + scipy are already
present; this reference beats a popularity baseline with them alone.

**Implicit feedback uses a BINARY matrix — this is the #1 thing to get right.** Set M[user,item]=1.0 for
every interaction; do NOT use the rating VALUE and do NOT mean-center (subtracting a user/item mean is for
EXPLICIT rating *prediction* — it destroys the implicit presence/absence signal and collapses recall to
near-zero). And **zero the similarity diagonal** (`np.fill_diagonal(S, 0.0)`, NOT 1.0) — an item is not its
own neighbor; leaving 1.0 on the diagonal pollutes every score. Copy the matrix + similarity below verbatim.

The method, and why each line matters:
1. **Binary interaction matrix** M (users × items): a row means the user interacted (implicit feedback).
2. **Item-item cosine** `S = (Mᵀ·M) / (‖·‖ outer)`, zero the diagonal — how similar each item pair is.
3. **Score** `M·S` = for each user, the summed similarity of every item to the ones they interacted with.
4. **Mask already-seen** (set their score to −∞) — recommend only UNSEEN items (a hard requirement).
5. **Top-K per user** by score → personalized (different users get different lists, unlike popularity).

    import csv, os
    import numpy as np

    DATA = "data/ratings.csv"                 # TASK-SPECIFIC: columns user_id,item_id[,rating]
    OUT  = "output/recommend/recommendations.csv"   # TASK-SPECIFIC: output path
    K = 10                                    # TASK-SPECIFIC: top-N

    rows = list(csv.DictReader(open(DATA)))
    users = sorted({r["user_id"] for r in rows}); items = sorted({r["item_id"] for r in rows})
    ui = {u: i for i, u in enumerate(users)};   ii = {it: j for j, it in enumerate(items)}
    M = np.zeros((len(users), len(items)), dtype=np.float32)
    for r in rows:
        M[ui[r["user_id"]], ii[r["item_id"]]] = 1.0

    norm = np.linalg.norm(M, axis=0) + 1e-9
    S = (M.T @ M) / np.outer(norm, norm)      # item-item cosine
    np.fill_diagonal(S, 0.0)
    scores = M @ S                            # users × items
    scores[M > 0] = -1e9                      # never recommend an already-seen item

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", newline="") as f:
        w = csv.writer(f); w.writerow(["user_id", "item_id", "rank"])
        for u in users:
            top = np.argsort(-scores[ui[u]])[:K]
            for rank, j in enumerate(top, 1):
                w.writerow([u, items[j], rank])

Verify (self-check before done): the output has exactly K rows per user; two different users have
DIFFERENT item lists (personalized, not one global popular list); no recommended item appears in that
user's input history. **CRITICAL popularity-collapse check — count the DISTINCT items recommended across
ALL users: it must be in the HUNDREDS, not a few dozen.** If only ~30–70 distinct items appear for the whole
population, you fell into the collapse: you weighted by the rating VALUE (`values='rating'`, `score += sim *
rating`) instead of the BINARY presence, and/or left the cosine diagonal at 1.0 — both amplify blockbusters
so everyone gets the same popular handful and held-out recall craters to near-zero (~0.007). Fix = binary
matrix (M[u,i]=1.0), `np.fill_diagonal(S, 0.0)`, mask seen. If memory is a concern for very large item
counts, use `scipy.sparse` for M and a sparse `M.T @ M`, but the dense form above is fine for a few thousand
items.

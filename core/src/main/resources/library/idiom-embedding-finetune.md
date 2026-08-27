# Fine-tune a text-embedding model (contrastive, in-batch negatives, plain transformers) — ONE complete reference (copy it whole; change only marked lines)

Train a base transformer encoder into a retrieval embedding model with **contrastive learning**. Use
**plain `transformers` + `torch`** — they are already installed. Do NOT `pip install sentence-transformers`:
it is not present and pulls a dependency tree that is slow to install and can fail inside a turn budget; the
whole objective below is ~15 lines of torch and needs nothing exotic. numpy/torch/transformers are enough.

**Two things decide whether this works — get both right:**
1. **Pooling: masked MEAN-pool over `last_hidden_state`, then L2-normalize** — and use the SAME pooling at
   train time and at encode/eval time. Do NOT use the `[CLS]` token for a plain masked-LM like distilbert
   (its raw CLS is not a sentence vector); mean-pool is the reference. Inconsistent pooling between train and
   eval silently destroys retrieval.
2. **The loss is in-batch-negatives cross-entropy (MultipleNegativesRankingLoss / InfoNCE):** embed the
   batch's queries `Q` and positives `P`, form the scaled similarity matrix `Q·Pᵀ · scale`, and apply
   `cross_entropy` with labels `[0,1,2,…]` — each query's positive is its own diagonal entry; every OTHER
   passage in the batch is a negative for free. This is why a LARGER batch trains better (more negatives).
   `scale≈20` (a.k.a. temperature 1/20) and `lr=2e-5` are the reliable defaults.

**TRAIN the given base — do not substitute an already-tuned embedder.** The task is to fine-tune the named
base encoder (e.g. `distilbert-base-uncased`, whose RAW retrieval is poor, nDCG@10≈0.13). Loading a
pre-trained MiniLM/BGE/E5 sentence-transformer and calling it done is not the task and is checked against.
A real fine-tune of distilbert on a few thousand pairs reaches nDCG@10 ≈ 0.55–0.61 (a ~0.45 jump).

    import json, os, random
    import torch, torch.nn.functional as F
    from transformers import AutoTokenizer, AutoModel

    BASE = "distilbert-base-uncased"                 # TASK-SPECIFIC: the base encoder named in the spec
    TRAIN = "data/train.jsonl"                        # TASK-SPECIFIC: {"query","positive"} per line
    OUT  = "output/embed-finetune/model"             # TASK-SPECIFIC: save_pretrained target
    LR, EPOCHS, BS, SCALE = 2e-5, 3, 32, 20.0        # reliable defaults; bigger BS = more in-batch negatives

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    tok = AutoTokenizer.from_pretrained(BASE)
    model = AutoModel.from_pretrained(BASE).to(dev)

    def embed(texts):                                # masked MEAN-pool + L2-normalize (SAME at train & eval)
        b = tok(texts, padding=True, truncation=True, max_length=256, return_tensors="pt").to(dev)
        o = model(**b)
        mask = b["attention_mask"].unsqueeze(-1).float()
        v = (o.last_hidden_state * mask).sum(1) / mask.sum(1)
        return F.normalize(v, dim=1)

    pairs = [json.loads(l) for l in open(TRAIN)]
    opt = torch.optim.AdamW(model.parameters(), lr=LR)
    model.train()
    for ep in range(EPOCHS):
        random.shuffle(pairs); tot = n = 0
        for i in range(0, len(pairs), BS):
            batch = pairs[i:i+BS]
            if len(batch) < 2: continue
            q = embed([b["query"] for b in batch])
            p = embed([b["positive"] for b in batch])
            scores = (q @ p.T) * SCALE               # in-batch negatives: [B,B]
            labels = torch.arange(len(batch), device=dev)   # positive = diagonal
            loss = F.cross_entropy(scores, labels)
            opt.zero_grad(); loss.backward(); opt.step()
            tot += loss.item(); n += 1
        print(f"epoch {ep+1} loss {tot/n:.4f}")       # loss should fall steadily (e.g. ~1.2 -> ~0.2)

    os.makedirs(OUT, exist_ok=True)
    model.save_pretrained(OUT); tok.save_pretrained(OUT)   # standard HF dir: AutoModel.from_pretrained(OUT)

Verify (self-check before done): loss dropped across epochs (not flat). Then encode a couple of eval queries
and the full corpus with the SAME mean-pool `embed()` and confirm the top hit for a query is an on-topic
passage — and that retrieval is now clearly better than the untrained base (base nDCG@10≈0.13, tuned ≈0.55+).
If retrieval is still near base, the usual cause is a pooling mismatch (CLS at eval vs mean at train) or the
loss never decreased (batch too small → too few negatives; raise BS). `output/embed-finetune/model/` must be
the fine-tuned distilbert saved via `save_pretrained`, not the base weights re-saved and not a swapped-in model.

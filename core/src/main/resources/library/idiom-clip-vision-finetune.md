# Fine-tune CLIP for image classification (multimodal vision+text, plain transformers) — ONE complete reference (copy it whole; change only marked lines)

Fine-tune a CLIP model to classify images into named categories. This is a MULTIMODAL (vision+text) task — use
**`transformers` `CLIPModel` + `CLIPProcessor`**, NOT `trl`. **Do NOT reach for `trl` / `SFTTrainer` / `SFTConfig`**:
those are for CAUSAL-LM text fine-tuning and do not apply to CLIP — importing them here just fails
(`SFTConfig not exported`, `Dataset undefined`) and wastes the run. CLIP trains with its OWN contrastive loss via
`model(..., return_loss=True)`; no Trainer class is needed.

**Two API facts to get right:**
1. **Train** with the processor feeding BOTH text and images, and `return_loss=True` — CLIP returns its symmetric
   image-text contrastive loss directly: `loss = model(**inputs, return_loss=True).loss`.
2. **Classify** with `model(**inputs).logits_per_image` (image-vs-text-prompt similarities), then `argmax` over the
   class prompts. Do NOT use `get_text_features` / `get_image_features` for this — in current transformers they can
   return a model-output object (not a tensor), so `.norm()`/matmul on them throws. `logits_per_image` is the robust path.

    import json, os, random, torch
    from PIL import Image
    from transformers import CLIPModel, CLIPProcessor

    MID = "openai/clip-vit-large-patch14"          # TASK-SPECIFIC: the CLIP checkpoint named in the spec (local cache)
    TRAIN_LABELS = "data/train_labels.jsonl"        # TASK-SPECIFIC: {"file","label"} per line
    TRAIN_DIR, TEST_DIR = "data/images/train", "data/images/test"
    OUT = "output/vision-finetune/predictions.jsonl"
    names = json.load(open("data/classes.json"))["names"]     # the full label set
    prompts = [f"a photo of a {n}" for n in names]             # one text prompt per class

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    proc = CLIPProcessor.from_pretrained(MID)
    model = CLIPModel.from_pretrained(MID).to(dev)

    tr = [json.loads(l) for l in open(TRAIN_LABELS) if l.strip()]
    tr_imgs = [Image.open(f"{TRAIN_DIR}/{r['file']}").convert("RGB") for r in tr]
    tr_txt  = [f"a photo of a {r['label']}" for r in tr]

    opt = torch.optim.AdamW(model.parameters(), lr=1e-5)       # small lr; full fine-tune is fine for a small set
    model.train()
    idx = list(range(len(tr)))
    for ep in range(6):                                        # a few epochs; contrastive loss should fall steadily
        random.shuffle(idx)
        for i in range(0, len(idx), 32):
            b = idx[i:i+32]
            inp = proc(text=[tr_txt[k] for k in b], images=[tr_imgs[k] for k in b],
                       return_tensors="pt", padding=True).to(dev)
            loss = model(**inp, return_loss=True).loss         # CLIP's built-in image-text contrastive loss
            loss.backward(); opt.step(); opt.zero_grad()

    model.eval()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    test_files = sorted(os.listdir(TEST_DIR))
    with open(OUT, "w") as f, torch.no_grad():
        for i in range(0, len(test_files), 16):
            batch = test_files[i:i+16]
            imgs = [Image.open(f"{TEST_DIR}/{fn}").convert("RGB") for fn in batch]
            inp = proc(text=prompts, images=imgs, return_tensors="pt", padding=True).to(dev)
            logits = model(**inp).logits_per_image             # [n_img, n_class]; argmax = predicted class
            for j, fn in enumerate(batch):
                f.write(json.dumps({"file": fn, "predicted_label": names[logits[j].argmax().item()]}) + "\n")

Verify (self-check before done): a pre-trained CLIP scores near chance on ARBITRARY class names (that is WHY you
fine-tune); after training, most test images classify to their true name (accuracy well above 1/len(names)).
`output/vision-finetune/predictions.jsonl` has one `{"file","predicted_label"}` line per test image, every label one
of `names`. If the loss never fell or accuracy is at chance, you trained too little (raise epochs) — do NOT switch to trl.

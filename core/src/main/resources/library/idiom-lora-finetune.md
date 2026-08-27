# Fine-tuning a small model (LoRA/PEFT SFT) — a STAGED recipe (do one stage at a time)

Fine-tuning is a MULTI-STAGE pipeline. Do NOT try to write it all at once — build and verify each stage on a
SMALL subset first, and TRAIN ONCE then reuse the saved adapter (never retrain on every run). Use **LoRA/PEFT
SFT** (don't full-fine-tune a small model — it overfits and forgets). The stages, in order:

## ONE format, DERIVED from the data — read this first (the #1 bug)
The system prompt and chat format are a **single source of truth, taken FROM the training data — never invented
per stage.** Read the system message out of `train.jsonl` ONCE and reuse that EXACT string in BOTH training and
inference. The most common silent failure: training on the data's real system message but then prompting at
inference with a different, hand-written one (e.g. a generic "You are a helpful assistant") — that puts the tuned
model off-distribution and it emits unparseable output, which an empty/placeholder fallback then hides. Derive the
prompt, don't hand-write it:

    import json
    first = json.loads(open("data/train.jsonl").readline())
    SYSTEM = next(m["content"] for m in first["messages"] if m["role"] == "system")  # the ONE system prompt

Use this same `SYSTEM` constant in train.py (it's already in the data) AND in infer.py's messages — never a second,
hand-written one. Same rule for the chat template: `tokenizer.apply_chat_template(...)` at inference must be the
same template the training used. One definition, both stages.

## Stage 1 — TRAIN (run ONCE, save the adapter to disk)
Write `train.py` as its OWN script so you don't retrain every time you debug inference. Validate it on a tiny
slice (e.g. 20 rows, 1 epoch) FIRST, confirm the loss drops, then run the full train.

    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig
    from trl import SFTTrainer, SFTConfig
    import json
    base = "Qwen/Qwen2.5-0.5B-Instruct"               # the base named in the spec
    tok = AutoTokenizer.from_pretrained(base)
    model = AutoModelForCausalLM.from_pretrained(base, torch_dtype="bfloat16", device_map="cuda")
    data = [json.loads(l) for l in open("data/train.jsonl")]   # each: {"messages":[{system},{user},{assistant}]}
    peft_cfg = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
                          target_modules=["q_proj","k_proj","v_proj","o_proj"])
    args = SFTConfig(output_dir="adapter", num_train_epochs=2, learning_rate=1e-5,   # lr 1e-5 — see COLLAPSE note
                     per_device_train_batch_size=4, gradient_accumulation_steps=4, bf16=True, logging_steps=10,
                     assistant_only_loss=True)         # mask the prompt — compute loss ONLY on the assistant turn
    trainer = SFTTrainer(model=model, args=args, train_dataset=data, peft_config=peft_cfg)   # applies the chat template
    trainer.train()                                    # CONFIRM the printed loss DROPS (else it's a no-op)
    trainer.save_model("adapter")                      # <-- the saved adapter; load it in Stage 2

**COMPLETION-ONLY LOSS is mandatory — it is the #1 cause of COLLAPSE** (the tuned model emits ONE constant answer
for every input). If loss covers the whole sequence (prompt + answer), the model learns to predict its own prompts
and collapses to a canonical response. Mask it to the assistant turn: `SFTConfig(assistant_only_loss=True)` (needs
a chat template); if your trl version lacks that flag, use a collator —
`DataCollatorForCompletionOnlyLM(response_template="<|im_start|>assistant", tokenizer=tok)` with YOUR base's
assistant marker. Conservative defaults that work for small models (hard-won): **2 epochs**, **lr ~1e-5 — do NOT
go higher; 5e-5 overfits a small model into collapse**, **lora_r 8–16**, **lora_alpha = 2×r**. The SFT data is
JSONL chat conversations `{"messages":[{user},{assistant}]}` (input→user, target→assistant). QLoRA if VRAM is tight.

## Stage 2 — INFERENCE (load the saved adapter; replay the TRAINING format EXACTLY)
Write `infer.py` that LOADS the adapter from Stage 1 (does NOT retrain). The #1 silent killer: an off-format
prompt. Prompt the tuned model with the SAME chat template + system message it was trained on, decode GREEDILY.

    from peft import PeftModel
    model = AutoModelForCausalLM.from_pretrained(base, torch_dtype="bfloat16", device_map="cuda")
    model = PeftModel.from_pretrained(model, "adapter")          # the trained adapter, not the bare base
    def run(text):
        msgs = [{"role":"system","content": SAME_SYSTEM_AS_TRAINING}, {"role":"user","content": text}]
        enc = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt")
        ids = (enc if hasattr(enc, "shape") else enc["input_ids"]).to(model.device)  # newer tf returns a BatchEncoding
        out = model.generate(ids, max_new_tokens=128, do_sample=False)     # GREEDY, not temperature 0.7
        return tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True) # decode only the NEW tokens

Batch the generation over all inputs (don't reload the model per row). `SAME_SYSTEM_AS_TRAINING` must be the
exact system content from the SFT data — an off-format prompt puts the model off-distribution → unparseable.

## Stage 3 — PARSE + FLATTEN to the target shape
Parse each output as JSON robustly. Emit the SAME shape as the training target (flat `{"k":"v",...}`, not nested
`{"k":{"name":"v"}}`) — a grader that exact-matches scores a correct value 0 if the shape differs. If the model
returns a nested entity, FLATTEN it to the scalar (take the obvious inner value). On a parse FAILURE, fix the
prompt/decoding — do **NOT** fall back to constant placeholders ("Sample"/"Unknown"/0 or `Field_{i}`): a
default-fill is a complete-looking file of FAKE rows that scores ~0 (the same trap as a stubbed core).

## Stage 4 — VERIFY it actually learned (the guards — where fine-tunes fail)
1. **Learned?** Stage-1 loss dropped meaningfully (flat loss = no-op: wrong target_modules / lr too low / data
   not tokenizing). Training loss dropping is NECESSARY but not sufficient — also check (2).
2. **Beats base?** Run BOTH the tuned model AND the bare base on a labeled HELD-OUT slice, compute the metric for
   each, and confirm the tuned model BEATS the base. If they tie, you shipped a no-op. (If the task gives you a
   `dev_inputs.jsonl`, predict on it and write `dev_predictions.jsonl` — the harness scores it on hidden labels.)
3. **Not collapsed?** Outputs VARY across inputs (not one repeated phrase / constant / `Field_{i}` formula). If
   they DO collapse (every input → the same answer), the fix is in Stage 1, not inference: (a) ensure
   completion-only loss (`assistant_only_loss=True` / the collator) so loss isn't computed on the prompt, and
   (b) LOWER the learning rate to 1e-5 (a too-high lr, e.g. 5e-5, memorizes one canonical output) — then retrain.

Litmus: loss dropped, tuned beats base on held-out, outputs are real and varied, deliverable shape == target shape.

## Complete reference — ONE correct script (copy it whole; change ONLY the two TASK-SPECIFIC lines)
Most failures come from RE-ASSEMBLING the pipeline by hand and getting one piece wrong (loss masking, the system
prompt, the adapter load). Don't reassemble from scratch — copy this complete, correct script and change only the
two lines marked TASK-SPECIFIC. The masking, lr, prompt-from-data, correct adapter load, greedy decode, and batch
loop are IDENTICAL for every SFT task. Run it in two steps: `python sft.py train`, then `python sft.py predict`.

    import json, sys
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig, PeftModel
    from trl import SFTTrainer, SFTConfig

    BASE = "<the base model named in your spec>"     # TASK-SPECIFIC #1: use the base the spec names
    TRAIN, TEST, ADAPTER, OUT = "data/train.jsonl", "data/test_inputs.jsonl", "adapter", "predictions.jsonl"

    def jsonl(p): return [json.loads(l) for l in open(p) if l.strip()]

    def system_prompt():                             # the format is GROUND TRUTH from the data — never invent it
        for m in jsonl(TRAIN)[0]["messages"]:
            if m["role"] == "system": return m["content"]
        return ""

    def train():
        model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16", device_map="auto")
        args = SFTConfig(output_dir=ADAPTER, num_train_epochs=2, learning_rate=1e-5,    # lr 1e-5; do not raise
                         per_device_train_batch_size=4, gradient_accumulation_steps=4, bf16=True,
                         logging_steps=10, assistant_only_loss=True, report_to="none")  # mask the prompt
        peft = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
                          target_modules=["q_proj","k_proj","v_proj","o_proj"])
        tr = SFTTrainer(model=model, args=args, train_dataset=jsonl(TRAIN), peft_config=peft)
        tr.train(); tr.save_model(ADAPTER)           # CONFIRM the printed loss DROPS (else it's a no-op)

    def load():                                      # base FIRST, THEN the adapter. The #1 load CRASH is calling
        tok = AutoTokenizer.from_pretrained(BASE)    # from_pretrained(ADAPTER) for the model — the adapter dir is
        model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16", device_map="auto")  # NOT a full model
        model = PeftModel.from_pretrained(model, ADAPTER); model.eval()
        return model, tok

    def predict():
        model, tok = load(); system = system_prompt()                 # SAME system prompt it trained on
        out = []
        for r in jsonl(TEST):
            msgs = [{"role": "system", "content": system}, {"role": "user", "content": r["text"]}]
            enc = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt")
            ids = (enc if hasattr(enc, "shape") else enc["input_ids"]).to(model.device)   # newer tf returns a BatchEncoding
            gen = model.generate(ids, max_new_tokens=128, do_sample=False)         # greedy; new tokens only:
            text = tok.decode(gen[0][ids.shape[1]:], skip_special_tokens=True).strip().strip("`").lstrip("json").strip()
            fields = json.loads(text)               # TASK-SPECIFIC #2: map text → the output schema your goal asks
            out.append({"id": r["id"], "fields": fields})   # for (flatten nested → scalar per Stage 3). On a parse
        with open(OUT, "w") as f:                   # error, FIX the prompt/decoding — never write a placeholder/empty row.
            for x in out: f.write(json.dumps(x) + "\n")

    if __name__ == "__main__":
        {"train": train, "predict": predict}[sys.argv[1]]()

Only TWO lines are task-specific (`BASE`, and the `json.loads`→schema mapping in `predict`). If the task gives a
`dev_inputs.jsonl`, add a second predict pass over it writing `dev_predictions.jsonl` (same code, different in/out).

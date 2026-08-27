# Reward modeling (trl RewardTrainer + LoRA) — ONE complete reference (copy it whole; change only marked lines)

A reward model scores a (prompt, response) pair with a SCALAR: load the base as a **1-label
sequence-classification** model (`AutoModelForSequenceClassification`, `num_labels=1`) and train on
preference pairs so score(chosen) > score(rejected). **`trl RewardTrainer` IS the trainer for this task**
— it is purpose-built to train a scorer from pairwise preferences, and `from trl import RewardTrainer,
RewardConfig` (top-level import) is the complete, correct API. DPOTrainer solves a DIFFERENT problem
(it tunes a generating policy, and produces no scorer) — a reward model is a SEQ_CLS scorer, so
RewardTrainer + `task_type="SEQ_CLS"` is the whole design. Don't reassemble the pipeline — copy this
complete script and change only the TASK-SPECIFIC lines; it runs as-is.

Key points (each one is a common failure if missed):

1. **`num_labels=1`** on the base load, and **`model.config.pad_token_id = tok.pad_token_id`** right
   after (Qwen has no default pad; RewardTrainer batches need it).
2. **Dataset format = implicit-prompt chat lists**: each row has ONLY `chosen` and `rejected`, each a
   full message list `[{"role":"user","content":prompt},{"role":"assistant","content":response}]`.
   Build them from your triples; do NOT pass bare strings or a separate prompt column.
3. **LoRA `task_type="SEQ_CLS"`** — not CAUSAL_LM; the score head trains along with the adapters.
4. Keep `RewardConfig` kwargs MINIMAL (trl versions differ on optional args — on "unexpected keyword
   argument", DROP that kwarg and use the default rather than guessing another name).
5. **`learning_rate=1e-4` and `num_train_epochs=2` ARE the conservative choice for a reward model** —
   the score head is freshly initialized and needs a real learning rate to train at all. A "cautious"
   5e-6 (the DPO policy-tuning lr) is NOT safer here: it leaves the head effectively untrained — training
   loss still drops by memorizing, but held-out ranking stays at coin-flip. Copy these two values as-is;
   2 epochs at 1e-4 is the whole schedule.
6. Save with `trainer.save_model(OUT)` — the adapter (`adapter_config.json` + `adapter_model.safetensors`)
   is what the verifier loads via `PeftModel.from_pretrained` on the SEQ_CLS base. **Then VERIFY the save**:
   open the saved safetensors and assert it contains `score` keys — the trained score head MUST be in the
   file or the adapter is unloadable:

       from safetensors import safe_open
       keys = list(safe_open(f"{OUT}/adapter_model.safetensors", "pt").keys())
       assert any("score" in k for k in keys), "score head missing — save the trainer.model (PEFT model), not a bare copy"

    import json
    from datasets import Dataset
    from transformers import AutoModelForSequenceClassification, AutoTokenizer
    from trl import RewardTrainer, RewardConfig
    from peft import LoraConfig

    BASE = "Qwen/Qwen2.5-0.5B-Instruct"          # TASK-SPECIFIC: base model
    DATA = "data/prefs_train.jsonl"              # TASK-SPECIFIC: preference triples
    OUT  = "output/reward-model/rm-adapter"      # TASK-SPECIFIC: adapter output dir

    tok = AutoTokenizer.from_pretrained(BASE)
    model = AutoModelForSequenceClassification.from_pretrained(BASE, num_labels=1, torch_dtype="bfloat16")
    model.config.pad_token_id = tok.pad_token_id

    rows = [json.loads(l) for l in open(DATA) if l.strip()]
    data = Dataset.from_list([{
        "chosen":   [{"role":"user","content":r["prompt"]}, {"role":"assistant","content":r["chosen"]}],
        "rejected": [{"role":"user","content":r["prompt"]}, {"role":"assistant","content":r["rejected"]}],
    } for r in rows])

    args = RewardConfig(output_dir=OUT, per_device_train_batch_size=4, num_train_epochs=2,
                        learning_rate=1e-4, logging_steps=10, report_to="none", max_length=512)
    peft = LoraConfig(r=16, lora_alpha=32, task_type="SEQ_CLS",
                      target_modules=["q_proj","k_proj","v_proj","o_proj"])
    trainer = RewardTrainer(model, args=args, train_dataset=data, processing_class=tok, peft_config=peft)
    trainer.train()          # log shows loss dropping + accuracy/margin rising
    trainer.save_model(OUT)

Verify (self-check before done): reload — `PeftModel.from_pretrained(AutoModelForSequenceClassification
.from_pretrained(BASE, num_labels=1, ...), OUT)` — then for a few held-back pairs, score each side
(`apply_chat_template(msgs, tokenize=False)` → tokenize → `model(**ids).logits[0][0]`) and confirm
score(chosen) > score(rejected) on most. Train on the FULL provided dataset, not a debug subset.

# KTO alignment (trl KTOTrainer + LoRA, UNPAIRED feedback) — ONE complete reference (copy it whole; change only marked lines)

KTO aligns a model from **unpaired binary feedback**: each row is a single (prompt, completion) marked
desirable (`label: true`) or undesirable (`label: false`) — no preference pairs. **`trl KTOTrainer` IS the
trainer for this task** — `from trl import KTOTrainer, KTOConfig` (top-level import; you may see a
FutureWarning about `trl.experimental` — it is safe to ignore, do NOT change the import). DPOTrainer needs
paired data and does not fit unpaired labels. It's a DPO-shaped pipeline (frozen base = reference, low lr,
beta) with a different data format. Don't reassemble it — copy this complete script; it runs as-is.

Key points (each is a common failure if missed):

1. **Dataset format = chat-list rows with a bool label**: `prompt` is a user message list, `completion` an
   assistant message list, `label` a bool. Build rows EXACTLY as below — the label mapping is the whole
   task: `label=True` MUST be the desirable completions as given in the data; never re-derive or flip it.
2. **`KTOConfig(beta=0.1, learning_rate=5e-6)`** — KTO uses a LOW lr like DPO (the policy shifts gently
   against the frozen reference). Keep kwargs MINIMAL: trl versions differ on optional args — on
   "unexpected keyword argument", DROP that kwarg rather than guessing another name.
3. **LoRA `task_type="CAUSAL_LM"`** — KTO tunes the generating policy (unlike a reward model's SEQ_CLS).
4. Save with `trainer.save_model(OUT)`; the verifier loads it via `PeftModel.from_pretrained` on the
   CausalLM base. Train on the FULL provided dataset, not a debug subset.

    import json
    from datasets import Dataset
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from trl import KTOTrainer, KTOConfig
    from peft import LoraConfig

    BASE = "Qwen/Qwen2.5-0.5B-Instruct"          # TASK-SPECIFIC: base model
    DATA = "data/feedback_train.jsonl"           # TASK-SPECIFIC: unpaired feedback rows
    OUT  = "output/kto-align/kto-adapter"        # TASK-SPECIFIC: adapter output dir

    tok = AutoTokenizer.from_pretrained(BASE)
    model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16")

    rows = [json.loads(l) for l in open(DATA) if l.strip()]
    data = Dataset.from_list([{
        "prompt":     [{"role": "user", "content": r["prompt"]}],
        "completion": [{"role": "assistant", "content": r["completion"]}],
        "label":      bool(r["label"]),
    } for r in rows])

    args = KTOConfig(output_dir=OUT, beta=0.1, learning_rate=5e-6, num_train_epochs=2,
                     per_device_train_batch_size=4, bf16=True, logging_steps=10, report_to="none")
    peft = LoraConfig(r=16, lora_alpha=32, task_type="CAUSAL_LM",
                      target_modules=["q_proj","k_proj","v_proj","o_proj"])
    trainer = KTOTrainer(model, args=args, train_dataset=data, processing_class=tok, peft_config=peft)
    trainer.train()          # watch rewards/margins rise in the log
    trainer.save_model(OUT)

Verify (self-check before done): reload — `PeftModel.from_pretrained(AutoModelForCausalLM.from_pretrained(BASE,
...), OUT)` — and for a few held-back prompts compare the summed response log-prob of a desirable vs an
undesirable completion under tuned MINUS base (the reward margin): the desirable side should win on most.
That tuned-minus-base margin is also how the dev check wants dev_predictions scored.

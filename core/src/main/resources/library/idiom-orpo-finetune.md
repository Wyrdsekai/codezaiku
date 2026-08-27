# ORPO alignment (trl ORPOTrainer + LoRA, reference-free) — ONE complete reference (copy it whole; change only marked lines)

ORPO aligns a model from preference PAIRS in ONE monolithic run — SFT loss plus an odds-ratio preference
penalty — with **no frozen reference model** (that is the point of ORPO; do not build or load a ref model,
and do not reach for DPOTrainer, which needs one). **`ORPOTrainer` IS the trainer for this task.**

**CRITICAL — the import path**: in this trl version ORPO lives under `trl.experimental`:

    from trl.experimental.orpo import ORPOTrainer, ORPOConfig

Know what your own checks will show, so you read them correctly:
- `dir(trl)` / `from trl import ORPOTrainer` will NOT show or find ORPOTrainer — **that is expected and does
  NOT mean ORPO is unavailable.** Experimental modules never appear at top level.
- The one check that settles it (run this, believe its result):
  `python3 -c "from trl.experimental.orpo import ORPOTrainer; print('ORPO available')"`
- A `TRLExperimentalWarning` on import is a WARNING, not an error — training proceeds normally
  (silence it with env `TRL_EXPERIMENTAL_SILENCE=1` if you like).
Do NOT fall back to writing a manual training loop — ORPOTrainer via the experimental path IS available
and IS the implementation.

Key points (each is a common failure if missed):

1. **Dataset format = pairwise chat-list rows**: `prompt` a user message list, `chosen` and `rejected` each
   an assistant message list. Map the data's chosen→chosen and rejected→rejected exactly — never re-derive,
   swap, or flatten the pairs into single rows (the pairwise loss needs both sides in ONE row).
2. **`ORPOConfig(beta=0.1, learning_rate=8e-6)`** — beta weighs the odds-ratio penalty; ORPO's lr is low,
   SFT-adjacent. Keep kwargs MINIMAL: on "unexpected keyword argument", DROP the kwarg, don't guess names.
3. **LoRA `task_type="CAUSAL_LM"` AND `target_modules` = ALL linear layers** — attention-only is not enough.
   ORPO's odds-ratio preference signal is weak on a small base + a few hundred pairs, so the adapter needs the
   FULL linear capacity: `["q_proj","k_proj","v_proj","o_proj","gate_proj","up_proj","down_proj"]`. This is the
   #1 silent failure: q_proj/v_proj-only underfits to ~0.27 preference-accuracy (BELOW the 0.50 reference — the
   tuned model ends up preferring REJECTED), and even q,k,v,o (attention-only) stalls at ~0.53 — both FAIL the
   grader. All-linear reaches ~0.98 with the exact same beta/lr/epochs. If the tuned model scores sub-0.5, you
   almost certainly left the MLP modules (gate/up/down) out of target_modules — add them and retrain.
4. Save with `trainer.save_model(OUT)`; the verifier loads via `PeftModel.from_pretrained` on the CausalLM
   base. Train on the FULL provided dataset, not a debug subset.

    import json
    from datasets import Dataset
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from trl.experimental.orpo import ORPOTrainer, ORPOConfig
    from peft import LoraConfig

    BASE = "Qwen/Qwen2.5-0.5B-Instruct"          # TASK-SPECIFIC: base model
    DATA = "data/prefs_train.jsonl"              # TASK-SPECIFIC: preference triples
    OUT  = "output/orpo-align/orpo-adapter"      # TASK-SPECIFIC: adapter output dir

    tok = AutoTokenizer.from_pretrained(BASE)
    model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16")

    rows = [json.loads(l) for l in open(DATA) if l.strip()]
    data = Dataset.from_list([{
        "prompt":   [{"role": "user", "content": r["prompt"]}],
        "chosen":   [{"role": "assistant", "content": r["chosen"]}],
        "rejected": [{"role": "assistant", "content": r["rejected"]}],
    } for r in rows])

    args = ORPOConfig(output_dir=OUT, beta=0.1, learning_rate=8e-6, num_train_epochs=2,
                      per_device_train_batch_size=4, bf16=True, logging_steps=10, report_to="none")
    peft = LoraConfig(r=16, lora_alpha=32, task_type="CAUSAL_LM",
                      target_modules=["q_proj","k_proj","v_proj","o_proj",   # ALL linear — MLP included
                                      "gate_proj","up_proj","down_proj"])    # attention-only underfits ORPO
    trainer = ORPOTrainer(model, args=args, train_dataset=data, processing_class=tok, peft_config=peft)
    trainer.train()          # watch rewards/accuracies rise
    trainer.save_model(OUT)

Verify (self-check before done): reload — `PeftModel.from_pretrained(AutoModelForCausalLM.from_pretrained(BASE,
...), OUT)` — and for a few held-back pairs compare the summed response log-prob of chosen vs rejected under
tuned MINUS base: chosen should win on most. That tuned-minus-base margin is also how the dev check wants
dev_predictions scored.

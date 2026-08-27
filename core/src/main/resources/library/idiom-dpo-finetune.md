# DPO preference alignment (trl DPOTrainer + LoRA) — ONE complete reference (copy it whole; change only marked lines)

DPO aligns a model to PREFERENCES: each row is `{prompt, chosen, rejected}` and training pushes the model to score
`chosen` higher than `rejected` (relative to a frozen reference = the base). It's a LoRA-SFT-shaped pipeline but with
`DPOTrainer`/`DPOConfig` instead of `SFTTrainer`, a preference dataset, and DPO-specific hyperparameters. The reference
model is the frozen base — `DPOTrainer` handles it automatically (with a PEFT model it disables the adapter to get the
ref). Don't reassemble it — copy this complete script and change only the two TASK-SPECIFIC lines.

## The DPO-specific settings that matter (different from SFT)
1. **`DPOConfig(beta=0.1, learning_rate=5e-6)`** — beta controls how far it moves from the reference (0.1 is standard);
   the lr is LOWER than SFT (~5e-6, not 1e-5/2e-4) because DPO on top of a reference is sensitive — a high lr diverges.
2. **The dataset is `{prompt, chosen, rejected}`** columns (strings). Pass a HF `Dataset`; DPOTrainer applies the
   chat template to `prompt` and tokenizes chosen/rejected itself. Do NOT pre-format into `messages`.
3. **The reward margin** = mean(reward(chosen) − reward(rejected)) must go POSITIVE as it trains (trl logs
   `rewards/margins`). That, not just the loss dropping, is the sign DPO is learning the preference.

## Complete reference — copy whole; change ONLY the two TASK-SPECIFIC lines. Run: `python dpo.py`
    import json, torch
    from datasets import Dataset
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import LoraConfig
    from trl import DPOTrainer, DPOConfig

    BASE = "<the base model named in your spec>"       # TASK-SPECIFIC #1: use the base the spec names
    TRAIN, ADAPTER = "data/prefs_train.jsonl", "output/dpo-align/dpo-adapter"   # TASK-SPECIFIC #2: adapter path from spec

    def jsonl(p): return [json.loads(l) for l in open(p) if l.strip()]

    tok = AutoTokenizer.from_pretrained(BASE)
    if tok.pad_token is None: tok.pad_token = tok.eos_token
    model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16", device_map="auto")
    data = Dataset.from_list([{"prompt": r["prompt"], "chosen": r["chosen"], "rejected": r["rejected"]}
                             for r in jsonl(TRAIN)])   # the {prompt,chosen,rejected} columns DPOTrainer expects

    args = DPOConfig(output_dir=ADAPTER, beta=0.1, learning_rate=5e-6, num_train_epochs=1,   # DPO: low lr, beta 0.1
                     per_device_train_batch_size=2, gradient_accumulation_steps=4, bf16=True,
                     logging_steps=10, report_to="none")   # keep DPOConfig kwargs MINIMAL: trl versions differ on the
                     # length args (max_prompt_length was removed in trl 1.7+) — if you hit "unexpected keyword
                     # argument", DROP that kwarg and use the default rather than guessing another name.
    peft = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
                      target_modules=["q_proj","k_proj","v_proj","o_proj"])
    trainer = DPOTrainer(model, args=args, train_dataset=data, processing_class=tok, peft_config=peft)  # ref = frozen base
    trainer.train()                                    # WATCH rewards/margins go POSITIVE (not just loss down)
    trainer.save_model(ADAPTER)                        # the LoRA adapter the grader loads with PeftModel.from_pretrained

    # VERIFY the preference was learned (do this — a loss drop alone isn't proof):
    from peft import PeftModel
    ref = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16", device_map="auto"); ref.eval()
    tuned = PeftModel.from_pretrained(AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16",
                                      device_map="auto"), ADAPTER); tuned.eval()
    def lp(m, prompt, resp):                            # sum log-prob of the RESPONSE tokens
        p = tok.apply_chat_template([{"role":"user","content":prompt}], add_generation_prompt=True, return_tensors="pt")
        r = tok(resp, return_tensors="pt", add_special_tokens=False).input_ids
        full = torch.cat([p, r], 1).to(m.device)
        with torch.no_grad(): logits = m(full).logits
        tok_lp = torch.log_softmax(logits[:, :-1].float(), -1).gather(2, full[:, 1:].unsqueeze(-1)).squeeze(-1)
        return tok_lp[0, p.shape[1]-1:].sum().item()
    wins = sum(((lp(tuned,x["prompt"],x["chosen"])-lp(ref,x["prompt"],x["chosen"])) >
                (lp(tuned,x["prompt"],x["rejected"])-lp(ref,x["prompt"],x["rejected"])))
               for x in jsonl(TRAIN)[:50])
    print("DPO preference-accuracy on train sample:", wins/50)   # want WELL above 0.5 (reference is 0.5 by construction)

## VERIFY (the DPO guards)
1. **Reward margin positive** — trl logs `rewards/chosen`, `rewards/rejected`, `rewards/margins`; margins must climb
   ABOVE 0. A dropping loss with margins ~0 = not learning the preference (lr too low / beta off / data mis-columned).
2. **Preference-accuracy > 0.5** — the length-invariant check above: the tuned model prefers chosen over rejected on
   the majority of pairs, MORE than the frozen reference (which is 0.5 by construction). Aim well above 0.5.
3. **Not diverged** — if the tuned model produces garbage/repetition, the lr is too high; DPO needs ~5e-6, lower than SFT.

Litmus: rewards/margins climb positive, preference-accuracy well above 0.5, adapter saved where the spec says.
Only TWO lines are task-specific (`BASE` and the `ADAPTER` path).

# PPO / RLHF (trl PPOTrainer + a trained reward model) — ONE complete reference (copy it whole; change only marked lines)

Classic RLHF: the policy GENERATES, a trained REWARD MODEL scores each completion, PPO updates the policy
toward higher reward while a frozen reference model bounds the drift (KL). **`PPOTrainer` IS the trainer
for this task**, and in this trl version it lives under `trl.experimental`:

    from trl.experimental.ppo import PPOTrainer, PPOConfig

Know what your own checks will show: `dir(trl)` will NOT list PPOTrainer and the top-level import raises
ImportError — that is EXPECTED, not unavailability; the experimental import above is the supported API
(the TRLExperimentalWarning is informational). Do NOT fall back to a manual RL loop.

Four models, one dataset — each line below is a common failure if missed:

1. **policy + ref_model** = two separate `AutoModelForCausalLM.from_pretrained(BASE)` loads.
2. **reward_model** = the TRAINED SEQ_CLS reward model. When one is provided as a LoRA adapter, load
   base-as-SEQ_CLS + PeftModel and **merge**: `PeftModel.from_pretrained(rm_base, RM_DIR).merge_and_unload()`.
   Never substitute a heuristic for a provided reward model.
3. **value_model** = a FRESH `AutoModelForSequenceClassification.from_pretrained(BASE, num_labels=1, ...)`;
   set `config.pad_token_id` on every SEQ_CLS model.
4. **Tokenizer**: `padding_side="left"`, and `tok.pad_token = tok.eos_token` if unset.
5. **Dataset rows are tokenized prompts** — plain `{"input_ids": [ints]}`. `apply_chat_template` may return
   a BatchEncoding (a UserDict, NOT a dict): unwrap with `if not isinstance(ids, list): ids = ids["input_ids"]`.
6. Keep `PPOConfig` kwargs MINIMAL (on "unexpected keyword argument", DROP the kwarg). `save_model` writes
   the FULL tuned policy directory — that is the artifact.

    import json
    from datasets import Dataset
    from transformers import AutoModelForCausalLM, AutoModelForSequenceClassification, AutoTokenizer
    from trl.experimental.ppo import PPOTrainer, PPOConfig
    from peft import PeftModel

    BASE   = "Qwen/Qwen2.5-0.5B-Instruct"        # TASK-SPECIFIC: base model
    DATA   = "data/prompts_train.jsonl"          # TASK-SPECIFIC: prompt rows
    RM_DIR = "data/rm-adapter"                   # TASK-SPECIFIC: the provided trained reward adapter
    OUT    = "output/ppo-align/ppo-model"        # TASK-SPECIFIC: full-model output dir

    tok = AutoTokenizer.from_pretrained(BASE, padding_side="left")
    if tok.pad_token is None: tok.pad_token = tok.eos_token
    policy = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16")
    ref    = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16")
    rm_base = AutoModelForSequenceClassification.from_pretrained(BASE, num_labels=1, torch_dtype="bfloat16")
    rm_base.config.pad_token_id = tok.pad_token_id
    reward_model = PeftModel.from_pretrained(rm_base, RM_DIR).merge_and_unload()
    value_model = AutoModelForSequenceClassification.from_pretrained(BASE, num_labels=1, torch_dtype="bfloat16")
    value_model.config.pad_token_id = tok.pad_token_id

    rows = [json.loads(l) for l in open(DATA) if l.strip()]
    def to_row(r):
        ids = tok.apply_chat_template([{"role": "user", "content": r["prompt"]}], add_generation_prompt=True)
        if not isinstance(ids, list): ids = ids["input_ids"]
        return {"input_ids": ids}
    data = Dataset.from_list([to_row(r) for r in rows])

    args = PPOConfig(output_dir=OUT, learning_rate=3e-6, total_episodes=600,
                     per_device_train_batch_size=8, response_length=64,
                     missing_eos_penalty=5.0, kl_coef=0.25,   # small-model PPO NEEDS the strong KL anchor
                     bf16=True, report_to="none")             # + EOS penalty, else it drifts to no-EOS collapse
    trainer = PPOTrainer(args=args, processing_class=tok, model=policy, ref_model=ref,
                         reward_model=reward_model, value_model=value_model, train_dataset=data)
    trainer.train()          # watch objective/scores RISE
    trainer.save_model(OUT)

Verify (self-check before done): reload the SAVED dir (`AutoModelForCausalLM.from_pretrained(OUT)`),
greedily generate on a few held-back prompts with tuned AND base — the tuned answers should be visibly
more concise/direct (the provided reward model's preference). Train on the FULL provided prompt set.

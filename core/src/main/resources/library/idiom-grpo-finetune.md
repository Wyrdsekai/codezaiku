# GRPO alignment (trl GRPOTrainer + LoRA, RL from a programmatic reward) — ONE complete reference (copy it whole; change only marked lines)

GRPO tunes a policy by GENERATING in the loop: for each prompt it samples a GROUP of completions, scores
them with a REWARD FUNCTION (plain Python you write from the task's reward spec), and updates toward the
higher-reward ones. No preference pairs, no reward model, no frozen reference to manage. **`GRPOTrainer` IS
the trainer for this task**, and in this trl version it is a TOP-LEVEL import (no experimental path needed):

    from trl import GRPOTrainer, GRPOConfig

Key points (each is a common failure if missed):

1. **Dataset rows are prompt-only chat lists**: `{"prompt": [{"role":"user","content": ...}]}`. No
   completions, no labels — the trainer generates its own completions.
2. **The reward function signature is exact**: `def my_reward(completions, **kwargs) -> list[float]`.
   Each element of `completions` is a MESSAGE LIST — the generated text is `completions[i][0]["content"]`.
   Implement the task's reward spec EXACTLY as written; do not invent a different reward.
3. **`GRPOConfig(num_generations=4, max_completion_length=64)`** — the group size and a short completion
   cap keep training fast; `learning_rate≈1e-5`. Keep kwargs MINIMAL: on "unexpected keyword argument",
   DROP the kwarg rather than guessing another name.
4. **Pass the model as the BASE ID string** with `peft_config` — `GRPOTrainer(BASE, reward_funcs=my_reward,
   args=..., train_dataset=..., peft_config=peft)` — and save with `trainer.save_model(OUT)`.
5. Watch `reward` / `rewards/.../mean` RISE in the training log — flat reward means the policy isn't
   moving (check the reward function returns varied floats, not constants).

    import json
    from datasets import Dataset
    from trl import GRPOTrainer, GRPOConfig
    from peft import LoraConfig

    BASE = "Qwen/Qwen2.5-0.5B-Instruct"          # TASK-SPECIFIC: base model
    DATA = "data/prompts_train.jsonl"            # TASK-SPECIFIC: prompt rows
    OUT  = "output/grpo-align/grpo-adapter"      # TASK-SPECIFIC: adapter output dir

    rows = [json.loads(l) for l in open(DATA) if l.strip()]
    data = Dataset.from_list([{"prompt": [{"role": "user", "content": r["prompt"]}]} for r in rows])

    def my_reward(completions, **kwargs):        # TASK-SPECIFIC: implement the goal's reward spec exactly
        out = []
        for c in completions:
            t = c[0]["content"]
            out.append(...)                      # the spec'd formula on t
        return out

    args = GRPOConfig(output_dir=OUT, learning_rate=1e-5, num_train_epochs=2,
                      per_device_train_batch_size=8, num_generations=4, max_completion_length=64,
                      bf16=True, logging_steps=10, report_to="none")
    peft = LoraConfig(r=16, lora_alpha=32, task_type="CAUSAL_LM",
                      target_modules=["q_proj","k_proj","v_proj","o_proj"])
    trainer = GRPOTrainer(BASE, reward_funcs=my_reward, args=args, train_dataset=data, peft_config=peft)
    trainer.train()          # watch the mean reward rise
    trainer.save_model(OUT)

Verify (self-check before done): reload the adapter (`PeftModel.from_pretrained` on the CausalLM base),
greedily generate on a few held-back prompts with tuned AND base, score both with your reward function —
the tuned mean must be clearly higher. Train on the FULL provided prompt set, not a debug subset.

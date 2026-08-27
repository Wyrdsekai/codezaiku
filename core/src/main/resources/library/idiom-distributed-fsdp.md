# Distributed fine-tune (torchrun + FSDP, 2 GPUs) — ONE complete reference (copy it whole; change only marked lines)

Distributed SFT = the SAME SFTTrainer script, launched with torchrun and given an fsdp config. Two
facts about THIS environment that your own errors will otherwise mislead you on:

1. **`"fsdp_version": 1` is REQUIRED in fsdp_config.** The default (FSDP2) fails on tied-embedding
   models (Qwen et al.) with `Parameter 'model.embed_tokens.weight' is shared with a parameter already
   managed by another FSDP group` — that error means ADD the fsdp_version pin, not that FSDP is broken
   and not that you should fall back to single-GPU or DDP.
2. **Launch with torchrun, not python**: `python3 -m torch.distributed.run --nproc_per_node=2
   --master_port=29671 train_fsdp.py`. Running the script bare trains single-process (not distributed);
   a busy master_port means pick another one, not abandon torchrun.

    import json
    from datasets import Dataset
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from trl import SFTTrainer, SFTConfig

    BASE = "Qwen/Qwen2.5-3B-Instruct"            # TASK-SPECIFIC: base model
    DATA = "data/train.jsonl"                    # TASK-SPECIFIC: chat-format rows
    OUT  = "output/distributed-sft/model"        # TASK-SPECIFIC: consolidated output dir

    rows = [json.loads(l) for l in open(DATA) if l.strip()]
    data = Dataset.from_list([{"messages": r["messages"]} for r in rows])
    args = SFTConfig(output_dir=OUT, num_train_epochs=2, per_device_train_batch_size=2,
                     gradient_accumulation_steps=2, learning_rate=1e-5, bf16=True, max_length=768,
                     logging_steps=20, report_to="none", save_strategy="no",
                     fsdp="full_shard auto_wrap",
                     fsdp_config={"transformer_layer_cls_to_wrap": ["Qwen2DecoderLayer"],
                                  "fsdp_version": 1})
    model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16")
    tok = AutoTokenizer.from_pretrained(BASE)
    trainer = SFTTrainer(model, args=args, train_dataset=data, processing_class=tok)
    trainer.train()
    trainer.save_model(OUT)                      # consolidates the shards on the main process
    if trainer.accelerator.is_main_process:
        tok.save_pretrained(OUT)

Inference afterwards is a SEPARATE single-process script: load the SAVED dir with
`AutoModelForCausalLM.from_pretrained(OUT, device_map="auto")`, and prompt with the SAME system
message the training data used (read it out of the first train row — an un-prompted tuned model
reverts to prose and fails format checks).

Verify (self-check before done): the training log shows two ranks; the saved dir loads; 2-3 held-back
inputs produce the trained output format.

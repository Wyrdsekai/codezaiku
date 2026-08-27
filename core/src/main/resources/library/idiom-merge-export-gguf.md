# Merge a LoRA adapter + export to GGUF (deployment packaging) — ONE complete reference (copy it whole)

Packaging a tuned adapter for deployment is TWO mechanical steps — merge, then convert. No training, no
GPU needed. Don't reassemble or improvise either step; copy this and change only the marked lines.

1. **Merge** = `PeftModel.from_pretrained(base, ADAPTER).merge_and_unload()` — this folds the LoRA deltas
   into the base weights and returns a plain model. Save THAT with `save_pretrained`, **and save the
   tokenizer into the same directory** — the GGUF converter reads the tokenizer from the model dir and
   fails without it (the most common miss).
2. **Convert** = llama.cpp's `convert_hf_to_gguf.py` pointed at the MERGED directory (never the adapter
   dir — an adapter alone is not a model). `--outtype q8_0` gives the standard 8-bit deployment quant.

    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from peft import PeftModel

    BASE    = "Qwen/Qwen2.5-0.5B-Instruct"       # TASK-SPECIFIC: base model
    ADAPTER = "data/kto-adapter"                 # TASK-SPECIFIC: the provided trained adapter
    OUT     = "output/merge-export/merged"       # TASK-SPECIFIC: merged HF dir

    base = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype="bfloat16")
    merged = PeftModel.from_pretrained(base, ADAPTER).merge_and_unload()
    merged.save_pretrained(OUT)
    AutoTokenizer.from_pretrained(BASE).save_pretrained(OUT)   # converter NEEDS the tokenizer files

Then convert (a shell command, not python):

    python3 ~/llama.cpp/convert_hf_to_gguf.py output/merge-export/merged \
        --outfile output/merge-export/model.gguf --outtype q8_0

Verify (self-check before done): the .gguf exists, starts with the 4 magic bytes `GGUF`, and is a
plausible size for the quant (a q8_0 0.5B is ~500MB — kilobytes means you converted the wrong dir).
If a llama.cpp server/cli is available, boot the gguf and confirm a one-prompt completion shows the
TUNED behavior (for a concision-tuned adapter: a short direct answer, not a paragraph).

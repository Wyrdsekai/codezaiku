# QLoRA fine-tuning (4-bit NF4 + LoRA SFT) — ONE complete reference (copy it whole; change only the marked lines)

> **The single most-mistaken setting: `learning_rate=2e-4`, NOT 1e-5.** QLoRA trains only tiny adapters on a frozen
> 4-bit base, so it uses ~20× the lr of plain small-model SFT. If you carry over 1e-5 from a regular-LoRA recipe, the
> adapter won't learn any transformation and your tuned model just matches the base. Use 2e-4.

QLoRA is exactly the LoRA-SFT pipeline with the base model **loaded in 4-bit** so a bigger model fits in far less
VRAM — you train small LoRA adapters on top of a frozen 4-bit base. Everything else (completion-only loss, lr,
prompt-from-data, adapter load, greedy decode) is IDENTICAL to plain LoRA SFT. The ONLY additions are: a
`BitsAndBytesConfig` (NF4 + double-quant) on the base load, `prepare_model_for_kbit_training`, and loading the SAME
4-bit base at inference. Don't reassemble it — copy this complete script and change only the two TASK-SPECIFIC lines.

## The three QLoRA-specific lines (everything else is normal LoRA SFT)
1. **4-bit load config** — `BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
   bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True)`. NF4 = the QLoRA paper's 4-bit type;
   compute in bf16; double-quant saves a little more. Pass it as `quantization_config=` to BOTH train and inference loads.
2. **`prepare_model_for_kbit_training(model)`** right after loading the 4-bit base, BEFORE attaching the LoRA — it
   casts layernorms to fp32 and enables gradient checkpointing so training on a quantized base is stable.
3. **Load the SAME 4-bit base at inference**, then attach the adapter — the adapter was trained against the 4-bit
   base; loading a full-precision base at inference is off-distribution.

`pip install bitsandbytes` (plus the usual `torch transformers peft trl accelerate`).

## PROVE it is really 4-bit (print the footprint — the harness reads this)
A 4-bit base has a memory footprint about **one-third** of fp16 (e.g. a 3B model ≈ 6 GB in fp16 but ≈ 2 GB in 4-bit).
Print it right after loading so it is verifiable, not assumed:

    print("MEMORY_FOOTPRINT_GB", round(model.get_memory_footprint() / 1e9, 2))   # 4-bit 3B ≈ ~2 GB, fp16 ≈ ~6 GB

If that number is fp16-sized, the 4-bit config did not take — fix the `quantization_config` before going further.

## Complete reference — copy whole; change ONLY the two TASK-SPECIFIC lines. Run: `python qlora.py train` then `python qlora.py predict`
    import json, sys, torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
    from peft import LoraConfig, PeftModel, prepare_model_for_kbit_training
    from trl import SFTTrainer, SFTConfig

    BASE = "<the base model named in your spec>"      # TASK-SPECIFIC #1: use the base the spec names
    TRAIN, TEST, ADAPTER, OUT = "data/train.jsonl", "data/test_inputs.jsonl", "adapter", "predictions.jsonl"
    BNB = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",     # the QLoRA 4-bit config — same for
                             bnb_4bit_compute_dtype=torch.bfloat16,            # train AND inference
                             bnb_4bit_use_double_quant=True)

    def jsonl(p): return [json.loads(l) for l in open(p) if l.strip()]

    def system_prompt():                              # the format is GROUND TRUTH from the data — never invent it
        for m in jsonl(TRAIN)[0]["messages"]:
            if m["role"] == "system": return m["content"]
        return ""

    def base_4bit():                                  # load the 4-bit base — used by BOTH train and predict
        model = AutoModelForCausalLM.from_pretrained(BASE, quantization_config=BNB, device_map="auto")
        print("MEMORY_FOOTPRINT_GB", round(model.get_memory_footprint() / 1e9, 2))   # PROVE 4-bit (~⅓ of fp16)
        return model

    def train():
        model = prepare_model_for_kbit_training(base_4bit())        # QLoRA: stabilize the quantized base, THEN LoRA
        args = SFTConfig(output_dir=ADAPTER, num_train_epochs=3, learning_rate=2e-4,   # KEEP 2e-4 — do NOT change to
                         # 1e-5. QLoRA needs ~20x the plain-SFT lr: only tiny adapters train on a FROZEN 4-bit base, so
                         # 1e-5 barely moves them and the model stays at BASE (learns easy verbatim fields, no transforms).
                         per_device_train_batch_size=4, gradient_accumulation_steps=4, bf16=True,
                         logging_steps=10, assistant_only_loss=True, report_to="none")  # mask the prompt (anti-collapse)
        peft = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
                          target_modules=["q_proj","k_proj","v_proj","o_proj"])
        tr = SFTTrainer(model=model, args=args, train_dataset=jsonl(TRAIN), peft_config=peft)
        tr.train(); tr.save_model(ADAPTER)            # CONFIRM the printed loss DROPS (else it's a no-op)

    def load():                                       # 4-bit base FIRST (SAME config it trained on), THEN the adapter
        tok = AutoTokenizer.from_pretrained(BASE)
        model = PeftModel.from_pretrained(base_4bit(), ADAPTER); model.eval()
        return model, tok

    def predict():
        model, tok = load(); system = system_prompt()              # SAME system prompt it trained on
        out = []
        for r in jsonl(TEST):
            msgs = [{"role": "system", "content": system}, {"role": "user", "content": r["text"]}]
            enc = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt")
            ids = (enc if hasattr(enc, "shape") else enc["input_ids"]).to(model.device)   # newer tf returns a BatchEncoding
            gen = model.generate(ids, max_new_tokens=128, do_sample=False)         # greedy; new tokens only:
            text = tok.decode(gen[0][ids.shape[1]:], skip_special_tokens=True).strip().strip("`").lstrip("json").strip()
            fields = json.loads(text)                 # TASK-SPECIFIC #2: map text → the output schema your goal asks
            out.append({"id": r["id"], "fields": fields})    # for. On a parse error FIX the prompt — never a placeholder.
        with open(OUT, "w") as f:
            for x in out: f.write(json.dumps(x) + "\n")

    if __name__ == "__main__":
        {"train": train, "predict": predict}[sys.argv[1]]()

## VERIFY (the QLoRA guards — same as SFT, plus the 4-bit one)
1. **4-bit took** — the printed `MEMORY_FOOTPRINT_GB` is ~⅓ of fp16 (a 3B ≈ ~2 GB, not ~6 GB). If it's fp16-sized,
   the quantization_config didn't apply.
2. **Learned** — Stage-1 training loss DROPS (flat loss = no-op: wrong target_modules / lr / data not tokenizing).
3. **Beats base** — run BOTH the tuned model and the bare base on the held-out inputs; the tuned model must WIN.
   (If the task gives `dev_inputs.jsonl`, also predict on it → `dev_predictions.jsonl`; the harness scores it.)
   If the tuned model only TIES base — it gets the easy verbatim fields but learns no TRANSFORMATION (e.g. a
   reformatted date/amount) — you almost certainly trained on a small DEBUG SUBSET. Validate on ~20 rows to get the
   pipeline running, then TRAIN ON ALL of `train.jsonl` for the real adapter: a 20–30 row train stays at base level.
4. **Not collapsed** — outputs VARY (not one constant). If they collapse: ensure `assistant_only_loss=True`
   (completion-only loss) and check the lr (see below), then retrain.

## QLoRA learning rate — ~2e-4, NOT the full-SFT 1e-5 (this is the key QLoRA difference)
QLoRA freezes the 4-bit base and trains ONLY the small LoRA adapters, so it needs a HIGHER adapter lr than
full/half-precision SFT — the QLoRA paper uses **~2e-4**. Full-precision small-model SFT uses ~1e-5 (higher there
overfits the whole model into collapse), but that value is **too low for QLoRA**: at 1e-5 the adapters barely move
in a couple epochs and the model under-learns / emits one degenerate output. Use **2e-4** for QLoRA; if it DOES
collapse to one repeated answer, ensure `assistant_only_loss=True` first (that, not lr, is the usual collapse cause).

Litmus: footprint is 4-bit-sized, loss dropped, tuned beats base on held-out, outputs varied and valid-JSON-shaped.
Only TWO lines are task-specific (`BASE`, and the `json.loads`→schema mapping in `predict`).

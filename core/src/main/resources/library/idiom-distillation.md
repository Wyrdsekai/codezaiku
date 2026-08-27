# Knowledge distillation (teacher labels → student SFT) — ONE complete reference (copy it whole)

Distillation = a LARGE teacher LABELS an unlabeled corpus, then a SMALL student is SFT'd on those labels to
inherit the skill. The key distinction from plain SFT: **you generate the training labels from the teacher —
they are NOT provided.** Two mechanical steps — label with the teacher, then SFT the student.

1. **Label with the teacher** (a served OpenAI-compatible endpoint): send the system prompt + the provided
   FEW-SHOT examples + each unlabeled input, temperature 0. The few-shots are what teach the teacher the exact
   output format/normalization — send them EVERY call. Parse the JSON out of each teacher reply.
2. **SFT the small student** on the teacher-labeled pairs (chat format), LoRA. **Copy the training config
   VERBATIM** — `per_device_train_batch_size=8`, 3 epochs, no `gradient_accumulation_steps`, no
   `assistant_only_loss`. On a SMALL student + a few-hundred-row set, the number of GRADIENT STEPS is what
   drives convergence: inflating the effective batch (e.g. batch 4 × accum 4) halves the steps and
   UNDER-trains the student (measured: effective-batch-16 → student 0.54, barely above baseline; the config
   below at batch-8 → 1.0). Do not "improve" these values. Save the STUDENT adapter — never the teacher.

    import json, os, re, urllib.request
    from datasets import Dataset
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from trl import SFTTrainer, SFTConfig
    from peft import LoraConfig

    STUDENT = "Qwen/Qwen2.5-0.5B-Instruct"           # TASK-SPECIFIC: the small student
    TEACHER_URL = "http://localhost:8201/v1/chat/completions"  # TASK-SPECIFIC: the served teacher
    OUT = "output/distill-extract/student-adapter"   # TASK-SPECIFIC: student adapter dir

    SYS = open("data/SYSTEM_PROMPT.txt").read()
    shots = [json.loads(l) for l in open("data/examples.jsonl") if l.strip()]   # 12 format-demo pairs
    unlab = [json.loads(l) for l in open("data/unlabeled.jsonl") if l.strip()]  # 300 inputs, NO labels

    def teacher_label(text):
        msgs = [{"role": "system", "content": SYS}]
        for s in shots:                                                          # few-shot EVERY call
            msgs += [{"role": "user", "content": s["text"]},
                     {"role": "assistant", "content": json.dumps(s["fields"])}]
        msgs.append({"role": "user", "content": text})
        body = json.dumps({"messages": msgs, "temperature": 0, "max_tokens": 96}).encode()
        req = urllib.request.Request(TEACHER_URL, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=120) as r:
            out = json.load(r)["choices"][0]["message"]["content"]
        m = re.search(r"\{.*\}", out, re.S)
        return m.group(0) if m else None

    silver = []
    for row in unlab:
        lab = teacher_label(row["text"])
        if lab:
            silver.append({"messages": [{"role": "system", "content": SYS},
                                        {"role": "user", "content": row["text"]},
                                        {"role": "assistant", "content": lab}]})

    tok = AutoTokenizer.from_pretrained(STUDENT)
    model = AutoModelForCausalLM.from_pretrained(STUDENT, torch_dtype="bfloat16")
    args = SFTConfig(output_dir=OUT, num_train_epochs=3, per_device_train_batch_size=8, learning_rate=2e-4,
                     bf16=True, logging_steps=25, report_to="none", max_length=512, save_strategy="no")
    peft = LoraConfig(r=16, lora_alpha=32, task_type="CAUSAL_LM",
                      target_modules=["q_proj","k_proj","v_proj","o_proj"])
    trainer = SFTTrainer(model, args=args, train_dataset=Dataset.from_list(silver), processing_class=tok, peft_config=peft)
    trainer.train()
    trainer.save_model(OUT)

Verify (self-check before done): reload the student adapter, run a few held-back inputs, confirm the outputs
match the teacher's format (normalized dates/amounts) — the student should now score FAR above its untrained
baseline. Label the FULL corpus, not a slice; the student is the 0.5B, never the teacher.

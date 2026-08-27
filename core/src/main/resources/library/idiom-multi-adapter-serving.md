# Serve one base model with MULTIPLE LoRA adapters, routing per request — ONE complete reference (copy it whole; change only marked lines)

Load ONE base model and several LoRA adapters onto it at once, then switch between them per request. This is the
multi-adapter serving pattern (cheaper than N full models). Use plain `transformers` + `peft`; no serving framework.

**The ONE thing that breaks this — get it exactly right.** Load the FIRST adapter with
`PeftModel.from_pretrained(base, path, adapter_name=...)`. Load EVERY ADDITIONAL adapter with
`model.load_adapter(path, adapter_name=...)` on the resulting PeftModel — **do NOT call
`PeftModel.from_pretrained` again** for the 2nd/3rd adapter. Calling `from_pretrained` a second time re-wraps the
already-wrapped model and the extra adapter is NOT registered, so routing to it silently falls back to base output
(the classic multi-adapter bug: the first adapter works, the rest don't). Then switch with `model.set_adapter(name)`
BEFORE each generation.

    import json, os, torch
    from transformers import AutoTokenizer, AutoModelForCausalLM
    from peft import PeftModel

    ADAPTERS = {"svc_a": "data/adapters/svc_a", "svc_b": "data/adapters/svc_b"}   # TASK-SPECIFIC: name -> dir
    PROBES = "data/probes.jsonl"                                                   # TASK-SPECIFIC: input requests
    OUT = "output/multi-adapter/responses.jsonl"                                   # TASK-SPECIFIC: output path

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    names = list(ADAPTERS)
    base_id = json.load(open(os.path.join(ADAPTERS[names[0]], "adapter_config.json")))["base_model_name_or_path"]
    tok = AutoTokenizer.from_pretrained(base_id)
    base = AutoModelForCausalLM.from_pretrained(base_id, dtype=torch.float32).to(dev).eval()

    # FIRST adapter: from_pretrained.  EVERY OTHER adapter: load_adapter (NOT from_pretrained again).
    model = PeftModel.from_pretrained(base, ADAPTERS[names[0]], adapter_name=names[0])
    for name in names[1:]:
        model.load_adapter(ADAPTERS[name], adapter_name=name)

    def generate(prompt, adapter_name):
        model.set_adapter(adapter_name)                     # route: activate the requested adapter FIRST
        t = tok.apply_chat_template([{"role": "user", "content": prompt}], tokenize=False, add_generation_prompt=True)
        enc = tok(t, return_tensors="pt").to(dev)
        with torch.no_grad():
            out = model.generate(**enc, max_new_tokens=16, do_sample=False)       # greedy = deterministic
        return tok.decode(out[0][enc["input_ids"].shape[1]:], skip_special_tokens=True)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    probes = [json.loads(l) for l in open(PROBES) if l.strip()]
    with open(OUT, "w") as f:
        for p in probes:
            resp = generate(p["prompt"], p["adapter"])
            f.write(json.dumps({"id": p["id"], "adapter": p["adapter"], "response": resp}) + "\n")

Verify (self-check before done): each adapter produces its OWN characteristic output, and two different adapters give
DIFFERENT responses to the same prompt (if they don't, the 2nd adapter didn't load — you called `from_pretrained`
twice instead of `load_adapter`). The output has one line per probe. Every probe was routed to the adapter its
request named (call `set_adapter` per request, not once).

# Batch inference through a GGUF model (llama.cpp server + concurrent requests) — ONE complete reference (copy it whole)

Offline batch-predict = serve the model once, drive ALL inputs through it concurrently, write one
structured row per input. Three mechanical pieces — server, worker pool, output — copy them as-is.

1. **Serve INSIDE the script**: boot llama-server with `subprocess.Popen` in the SAME python script
   that runs the batch, then POLL `/health` until it returns ok. Do NOT start the server as its own
   shell command — each command's processes are cleaned up when it finishes, so a server started in
   one command is dead by the next; curls from later commands always get connection refused.
2. **Greedy only** (`"temperature": 0`): the verifier regenerates a sample through the same model and
   requires your stored predictions to MATCH — any sampling temperature makes that impossible.
3. **Concurrency = a small thread pool** (match the server's `-np` slots). 500 sequential requests is
   the common time-waster; 4 workers finish the batch in seconds on GPU.
4. **Every id, none empty** — collect results keyed by id and write ALL of them; a dropped or empty row
   is a completeness failure. Kill the server when done.

    import json, subprocess, time, urllib.request
    from concurrent.futures import ThreadPoolExecutor

    GGUF = "data/model.gguf"                     # TASK-SPECIFIC: the provided model
    INPUTS = "data/inputs.jsonl"                 # TASK-SPECIFIC: {"id", "prompt"} lines
    OUT = "output/batch-predict/predictions.jsonl"  # TASK-SPECIFIC: output path
    PORT = 8080

    proc = subprocess.Popen(["llama-server", "-m", GGUF,
                             "--port", str(PORT), "--host", "127.0.0.1", "-ngl", "99", "-np", "4"])
    for _ in range(90):                          # wait for the model to load
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as r:
                if b"ok" in r.read(): break
        except Exception:
            time.sleep(2)

    def chat(prompt):
        body = json.dumps({"messages": [{"role": "user", "content": prompt}],
                           "max_tokens": 64, "temperature": 0}).encode()
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}/v1/chat/completions",
                                     data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.load(r)["choices"][0]["message"]["content"]

    rows = [json.loads(l) for l in open(INPUTS) if l.strip()]
    with ThreadPoolExecutor(max_workers=4) as ex:
        preds = list(ex.map(lambda r: {"id": r["id"], "prediction": chat(r["prompt"])}, rows))
    with open(OUT, "w") as f:
        for p in preds:
            f.write(json.dumps(p) + "\n")
    proc.kill()

Verify (self-check before done): the output has exactly as many rows as the input, every prediction
non-empty, and spot-re-requesting 2-3 prompts returns the SAME text (greedy is deterministic — if it
differs, you sampled).

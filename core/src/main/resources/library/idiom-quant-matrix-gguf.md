# Quantization matrix (llama.cpp convert + llama-quantize) — ONE complete reference (copy it whole)

A quant matrix = convert the model to a high-precision GGUF ONCE, then run `llama-quantize` from that
single f16 source to each target, and report the measured sizes. Three mechanical rules:

1. **One f16 source, N quantize passes.** Never convert per-quant, and never quantize FROM a quantized
   file (q4-from-q8 double-quantizes and degrades) — always f16 → target:

       python3 ~/llama.cpp/convert_hf_to_gguf.py <merged_dir> --outfile f16.gguf --outtype f16
       ~/llama.cpp/build/bin/llama-quantize f16.gguf model-q8_0.gguf   q8_0
       ~/llama.cpp/build/bin/llama-quantize f16.gguf model-q4_K_M.gguf q4_K_M
       ~/llama.cpp/build/bin/llama-quantize f16.gguf model-q3_K_M.gguf q3_K_M

2. **Report MEASURED numbers**: sizes from `os.path.getsize(path)/1e6`, never estimates — verifiers
   compare the report against the actual files. Sane 0.5B landmarks: f16 ~1GB, q8_0 ~530MB,
   q4_K_M ~400MB, q3_K_M ~355MB; sizes must DESCEND with bit-width.
3. **Sanity-boot one quant** before calling it done: llama-server + one greedy completion — started
   INSIDE the same script that sends the request (a server started as its own shell command is dead
   by the next command; poll /health before sending).

    import json, os
    quants = ["q8_0", "q4_K_M", "q3_K_M"]
    report = [{"quant": q, "size_mb": round(os.path.getsize(f"output/quant-matrix/model-{q}.gguf") / 1e6, 1)}
              for q in quants]
    json.dump(report, open("output/quant-matrix/report.json", "w"), indent=1)

Verify (self-check before done): each .gguf starts with the magic bytes `GGUF`; sizes descend
q8_0 > q4_K_M > q3_K_M; report.json parses and its size_mb values match the files.

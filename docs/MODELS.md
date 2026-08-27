# Models

CodeZaiku ships **no model weights** and does not run an inference server. It talks to any
OpenAI-compatible `/v1/chat/completions` endpoint — llama.cpp, Ollama, vLLM, LM Studio, or a hosted API.

**Why we don't manage the server for you.** Doing it properly means owning GPU and driver detection
across CUDA/ROCm/Metal, VRAM sizing, quantization choice, and multi-gigabyte downloads — a bigger support
surface than the rest of CodeZaiku, for work that llama.cpp and Ollama already do well. Every bug in
their server would become a bug in ours. So we own the part that is actually ours: finding the servers
you already run, keeping several under names, and switching between them.

```bash
codezaiku model detect            # find what is running locally
codezaiku model add big http://localhost:8201
codezaiku model use big           # point the drive at it, and report what it serves
codezaiku model list
codezaiku smoke                   # the check that matters — can it call a tool
```

`model detect` probes the usual local ports (Ollama 11434, llama.cpp 8200/8080, LM Studio 1234,
vLLM 8000, text-generation-webui 5000) and tells you what each one is serving. If nothing is running it
prints the two commands most likely to get you going.

`model use` probes before switching, so a dead endpoint is reported now rather than becoming a confusing
failure in your next command.

---

## What the model must be able to do

Two hard requirements and one soft one.

**1. Tool calling.** The whole harness is a tool-calling loop. A model that cannot emit structured tool
calls cannot drive it at all. With llama.cpp you need `--jinja` so the server applies the model's own
chat template; without it, tool calls come back as prose and nothing works.

**2. A context window of at least 8k for interactive use, 12k to be driven by another agent, 32k if
you have it.** The loop keeps a growing conversation and compacts it, but small windows force
compaction so often that the model loses the thread. `codezaiku doctor` reports the window it detects
and flags both floors.

The second number is not a guess. A host dispatch carries its own task preamble on top of the pinned
project block, and one measured at 8k refused before its first turn — 9,619 tokens into 8,192. The
request is bounded before it is sent rather than retried, so it fails as one clean refusal naming
both counts, not as a run that quietly degrades. Provision 16k for a backend.

**3. Instruction-following on long, structured prompts** (soft). The operator prompts carry sensor
output, logs and card content; models that drift on long inputs localize badly.

Model *size* matters less than these three. A 7–9B that calls tools cleanly beats a larger model that
does not.

---

## What we measured on

Every number in [LIMITATIONS.md](LIMITATIONS.md) came from one of two models:

| Tier | Model | Where it was used |
|---|---|---|
| **9B (reference)** | a Qwen-3.5-9B derivative, Q4_K_M | the default drive for nearly all results |
| **30B** | `qwen3-coder-30b-a3b-instruct`, Q8_0 | the larger-model control, and a second ops tier |

**Your results will differ with a different model, and we have not measured yours.** The comparisons in
LIMITATIONS.md hold *within* our setup — they were designed to isolate harness from model, not to rank
models. Treat published numbers as "what this harness did with that model", not as a spec.

One measured note that generalizes: the 30B is a **~3B-active MoE coder**. It was *not* a clean
model-size control for knowledge-heavy work, and on research tasks it did no better than the 9B. Bigger
parameter counts on the label do not mean more capability for a given job.

---

## Reasonable starting points

We have not benchmarked these; they are the families that meet the requirements above and are commonly
run locally. Prefer an **instruct/chat** build with tool-calling support.

| If you want | Try |
|---|---|
| Coding work on modest hardware | a 7–9B coder-instruct, Q4_K_M or better |
| Ops/security operation | same tier — the operator leans on deterministic sensors, not model scale |
| Best available locally | a 30B-class instruct model if you have the VRAM |
| No local GPU | any hosted OpenAI-compatible endpoint — see [Using a hosted endpoint](#using-a-hosted-endpoint) |

Quantization: **Q4_K_M is the practical floor** and what our 9B results used. Below that, instruction
following and tool-call formatting degrade before general fluency does — which shows up as a harness that
"mysteriously stops working" rather than as obviously worse prose.

---

## Running a server

llama.cpp, the setup our measurements used:

```bash
docker run -d --name codezaiku-drive -p 8200:8200 \
  -v /path/to/models:/models \
  ghcr.io/ggml-org/llama.cpp:server-cuda \
  -m /models/<your-model>.gguf \
  --port 8200 --host 0.0.0.0 \
  --jinja \                 # REQUIRED — enables the model's tool-call template
  --ctx-size 32768 \
  --n-gpu-layers 99
```

Ollama:

```bash
ollama serve
export CODEZAIKU_DRIVE=http://localhost:11434
export CODEZAIKU_MODEL=<the tag you pulled>    # e.g. qwen3-coder:30b
```

`CODEZAIKU_MODEL` is the `model` field of each request. llama.cpp ignores it and serves whatever
weights it loaded, so you can leave it alone there — but **Ollama, LM Studio, vLLM and hosted APIs use
it to choose the model**, and will reject a name they do not have. `codezaiku model detect` prints the
names each endpoint is serving.

Then:

```bash
codezaiku doctor    # confirms the endpoint answers and reports the context window
codezaiku smoke     # confirms the model can actually emit a tool call
```

`smoke` is the real check. `doctor` proves the server is reachable; `smoke` proves the model does the one
thing the harness cannot work without.

---

## Using a hosted endpoint

Anything OpenAI-compatible works, and most hosted endpoints want a credential:

```bash
export CODEZAIKU_DRIVE=https://api.example.com     # the BASE — no /v1, no /chat/completions
export CODEZAIKU_MODEL=<the model id the provider expects>
export CODEZAIKU_API_KEY=sk-...
codezaiku doctor                                   # confirms the endpoint answers, with the key
```

`CODEZAIKU_API_KEY` is sent as `Authorization: Bearer <key>` on every request, including the health
probe. Give it bare or already prefixed with `Bearer ` — both work, and it will not be doubled. Leave
it unset for a local server: llama.cpp and Ollama want no header at all.

Three things to get right, because each one fails in a way that looks like something else:

- **`CODEZAIKU_MODEL` is required here.** llama.cpp serves whatever it loaded and ignores the field,
  so it is optional locally. A hosted provider uses it to *choose* the model and rejects the request
  without it.
- **Point `CODEZAIKU_DRIVE` at the base, not the endpoint.** CodeZaiku appends
  `/v1/chat/completions` itself, so a URL that already carries `/v1` becomes
  `/v1/v1/chat/completions` and 404s. `codezaiku doctor` names this one specifically.
- **A missing key reads as a missing server.** Without a credential a hosted endpoint answers 401,
  and a health probe that only asks "did anything answer" reports the server as down. `codezaiku
  doctor` sends the key, so run it before concluding the endpoint is unreachable.

Environment beats any config file, so a host spawning CodeZaiku can set the credential per
invocation without touching the box. `codezaiku config list` shows whether a key is configured and
where it came from, but never prints it.

**A hosted model is not what the numbers here describe.** Everything in
[LIMITATIONS.md](LIMITATIONS.md) was measured on a local 9B. A frontier model behind an API will do
better at the coding surface, and the harness has not been measured that way — treat it as untested
rather than as an upgrade with a known size.

## When another system drives CodeZaiku

Driven over MCP as a component of a larger system, the model choice belongs to that system, not to
CodeZaiku — it uses whatever endpoint the host points it at. The settings here apply when you run
CodeZaiku directly.

---

## Two models at once (optional)

Some paths can hand a hard sub-problem to a larger model while the small one drives:

```bash
export CODEZAIKU_DRIVE=http://localhost:8200           # the 9B does the work
export CODEZAIKU_DISTILLER_URL=http://localhost:8201   # a 30B answers one-shot localization
```

This is optional and off by default. It was worth it for some coding localization hand-offs; it did
nothing for research.

---

## Embeddings

The knowledge library indexes with Lucene (lexical) and does not require an embedding model. If you run
one for other purposes, CodeZaiku does not need it.

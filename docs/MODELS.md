# Models

CodeZaiku ships **no model weights** and does not run an inference server. It talks to any
OpenAI-compatible `/v1/chat/completions` endpoint — llama.cpp, Ollama, vLLM, LM Studio, or a hosted API.

**The server, on demand.** `codezaiku model serve install` sets a model server up on this machine for the
card it finds: it picks the measured row for the card's memory (the table below), downloads the file once and
checks it against a recorded sha256, and puts llama.cpp behind a small proxy that starts the model when
something asks and stops it after twenty idle minutes. Linux uses llama.cpp's CUDA container, macOS its Metal
build as a launchd agent, Windows its Vulkan build as a logon task. `codezaiku setup` offers it when it finds
no server. Everything else stays yours: the servers you already run, kept under names, switched between.

```bash
codezaiku setup                   # the first ten minutes: finds a server, or serves one, or takes a key
codezaiku model serve install     # the model on this machine, on demand; status · stop · uninstall · check
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

## By VRAM

What `model serve install` picks, best first. VRAM is the memory on the graphics card, not the computer's
RAM; `nvidia-smi` shows it, and on a Mac with Apple silicon read the tiers against about two thirds of the
unified memory. The rows come from ResearchZosho's measurement of eleven
models on the same research questions (the facts right, claims made, citations the checker could read, time
per question) and from CodeZaiku's own coding probes on the two we drive with. A row marked *research* was
measured reading and writing, not coding; it calls tools cleanly, which is what the harness needs first.

| VRAM | model | file | what we saw |
|---|---|---|---|
| 24 GB or more | Qwen3.8-27B at 4-bit | `Qwen3.8-27B-UD-Q4_K_M.gguf` | the reference drive: 93% of tool-call probes acted on the right tool on time, the only local model green across the coding reverse-eval; 17 GB file, 3.6 s median turn |
| 16 GB | gpt-oss-20b | `gpt-oss-20b-F16.gguf` | *research*: right, fast (about 5 minutes a question), 13 GB in use with two 16k slots |
| 8 GB | Qwen3.5 9B at 4-bit | `Qwen3.5-9B-Q4_K_M.gguf` | the drive behind nearly every number in LIMITATIONS.md; calls tools cleanly; multi-file coding ceiling about 45% |
| 4 GB | Gemma 4 E4B at 4-bit | `gemma-4-E4B-it-Q4_K_M.gguf` | *research*: right, the best citation reader of the small models; 3.6 GB in use |
| 2 GB | Gemma 4 E2B at 4-bit | `gemma-4-E2B-it-Q4_K_M.gguf` | *research*: facts right, write-ups thin. A hosted API is the better answer this small |

The files are the ones Hugging Face lists under `unsloth/<model>-GGUF`. The full research list, with the
second and third choices per tier and the models measured and not recommended, is
[ResearchZosho's models page](https://researchzosho.org/models/).

One measured note that generalizes: a 30B-class coder that is a ~3B-active mixture did no better than the
9B on knowledge-heavy work. Bigger parameter counts on the label do not mean more capability for a given job;
the 27B dense model is the reference because it acts on tools reliably, not because it is larger.

---

## What the model must be able to do

Two hard requirements and one soft one.

**1. Tool calling.** The whole harness is a tool-calling loop. A model that cannot emit structured tool
calls cannot drive it at all. With llama.cpp you need `--jinja` so the server applies the model's own
chat template; without it, tool calls come back as prose and nothing works.

**2. A context window of at least 8192 tokens, 12288 if another program drives CodeZaiku, 32768 if
you have it.** The conversation grows as the model works. CodeZaiku shortens it when it gets long, but
with a small window that happens so often that the model loses track of what it was doing.
`codezaiku doctor` reports the window it finds and says whether it meets both numbers.

The second number comes from a measurement. When an editor or another agent sends CodeZaiku a task
(through `run`, MCP or ACP), the task comes with its own instructions, and they take room in the
context. At 8192 tokens one such task needed 9,619 tokens before the model's first turn, so it was
refused. CodeZaiku checks the size before sending, so this fails once with a clear message rather than
running badly. Give a model server 16384 tokens when other programs will use it.

**3. Instruction-following on long, structured prompts** (soft). The operator prompts carry sensor
output, logs and card content; models that drift on long inputs localize badly.

Model *size* matters less than these three. A 7–9B that calls tools cleanly beats a larger model that
does not.

---

## What we measured on

Every number in [LIMITATIONS.md](LIMITATIONS.md) came from one of two models:

| Tier | Model | Where it was used |
|---|---|---|
| **9B** | a Qwen-3.5-9B derivative, Q4_K_M | the drive for nearly all the LIMITATIONS.md results |
| **30B** | `qwen3-coder-30b-a3b-instruct`, Q8_0 | the larger-model control, and a second ops tier |
| **27B (reference since 0.2.0)** | `Qwen3.8-27B-UD-Q4_K_M.gguf` | the chat, tool-call probe and coding reverse-eval numbers; what `model serve install` picks on a 24 GB card |

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
| Best available locally | the 27B row above, on a card with 24 GB of VRAM |
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

# Models

CodeZaiku ships **no model weights** and does not run an inference server. It talks to any
OpenAI-compatible `/v1/chat/completions` endpoint — llama.cpp, Ollama, vLLM, LM Studio, or a hosted API. This page has a measured recommendation for both kinds: the local models by your card,
first, and then the hosted models by what they passed, how long a job took and what it cost, at prices read in October 2026.

**The server, on demand.** `codezaiku model serve install` sets a model server up on this machine for the
card it finds: it picks the measured row for the card's memory (the table below), downloads the file once and
checks it against a recorded sha256, and puts llama.cpp behind a small proxy that starts the model when
something asks and stops it after twenty idle minutes. Linux uses llama.cpp's container for the GPU it finds:
CUDA on an NVIDIA card, ROCm on an AMD Instinct card, Vulkan on a Radeon, a Ryzen AI chip or an Intel GPU (Docker
with the kernel's render devices passed through; no NVIDIA runtime needed). macOS uses its Metal build as a
launchd agent, Windows its Vulkan build as a logon task. On Linux the model's container has no way out to the network,
and the install tries that before it finishes; the address the model is used at, `http://127.0.0.1:8211`, is the
proxy's and stays the same ([SECURITY.md](../SECURITY.md), "A model on your own machine"). `codezaiku model serve status`
says which way it is set up; one set up by an earlier version changes when you run `model serve install` again.
Other programs, ResearchZosho among them, use the server by that address; CodeZaiku's model folder, `~/.codezaiku/model`, is
CodeZaiku's alone, and `model serve uninstall` removes only what its install put there.
`codezaiku setup` offers it when it finds no server. Everything else stays yours: the servers you already run, kept under names, switched between.

```bash
codezaiku setup                   # the first ten minutes: finds a server, or serves one, or takes a key
codezaiku model serve install     # the model on this machine, on demand; status · stop · uninstall · check
codezaiku model detect            # find what is running locally
codezaiku model add big http://localhost:8201
codezaiku model use big           # point the drive at it, and report what it serves
codezaiku model add deepseek openrouter deepseek/deepseek-v4.1-flash --prices 0.14/0.42/0.0042 --cap 20
codezaiku model key openrouter --file ~/openrouter-key.txt
codezaiku code . "add a --json flag" --model deepseek     # this job on that model; the default stays
codezaiku model list
codezaiku spend                   # what the jobs cost, by host and by saved model
codezaiku smoke                   # the check that matters — can it call a tool
```

`model detect` probes the usual local ports (Ollama 11434, llama.cpp 8200/8080, LM Studio 1234,
vLLM 8000, text-generation-webui 5000) and tells you what each one is serving. If nothing is running it
prints the two commands most likely to get you going.

`model use` probes before switching, so a dead endpoint is reported now rather than becoming a confusing
failure in your next command.

A model on OpenRouter is served by the providers OpenRouter picks, less those based in, running in or owned from the
countries on the avoid list ([SECURITY.md](../SECURITY.md), "The places CodeZaiku avoids"). `model add` and `model list` say how
many providers serve the model and which are left out; when none is left, a job says so and sends nothing.

---

## By VRAM

What `model serve install` picks, best first. VRAM is the memory on the graphics card, not the computer's
RAM; `nvidia-smi` shows it on an NVIDIA card, and on an AMD GPU it is what the BIOS set aside for it (a Ryzen AI
MAX with 32 GB set aside reads as 32 GB). On a Mac with Apple silicon read the tiers against about two thirds of the
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

The files are the ones Hugging Face lists under `unsloth/<model>-GGUF`, each taken at a fixed revision of its
repository, the one it was measured at: a file made again upstream under the same name does not change what an
install downloads. Each row also records the file's sha256 and size, so `doctor` and `setup` say how big the download is
without asking anyone. The full research list, with the
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

Or save it under a name and keep the key for its host (below). Three APIs have clients of their own, because their OpenAI-style
endpoints lack something: the Claude API (`drive = https://api.anthropic.com`, the Messages API: prompt caching, the effort
setting, a summary of the thinking), OpenAI (`https://api.openai.com`, the Responses API: its current models refuse tools on the
chat-completions endpoint, since they carry their reasoning from one call to the next) and Amazon Bedrock (`drive = bedrock`,
see [BEDROCK.md](BEDROCK.md)). Everything else — OpenRouter, Gemini, Nebius, Hugging Face's router, DeepSeek, Mistral, Groq, xAI,
Together, Fireworks, Moonshot — is driven through the OpenAI chat format, with the request shaped as each takes it: no llama.cpp
fields, on OpenRouter only the fields the model's endpoints take, a rate limit waited out, an error inside a 200 reported as one.

`CODEZAIKU_API_KEY` is sent as `Authorization: Bearer <key>` on every request, including the health
probe. Give it bare or already prefixed with `Bearer ` — both work, and it will not be doubled. Leave
it unset for a local server: llama.cpp and Ollama want no header at all.

Three things to get right, because each one fails in a way that looks like something else:

- **`CODEZAIKU_MODEL` is required here.** llama.cpp serves whatever it loaded and ignores the field,
  so it is optional locally. A hosted provider uses it to *choose* the model and rejects the request
  without it.
- **Point `CODEZAIKU_DRIVE` at the base, not the endpoint.** CodeZaiku appends
  `/v1/chat/completions` itself, so a URL that already carries `/v1` becomes
  `/v1/v1/chat/completions` and 404s. `codezaiku doctor` names this one specifically. Gemini's root,
  `https://generativelanguage.googleapis.com/v1beta/openai`, is the one exception and is kept as it is.
- **A missing key reads as a missing server.** Without a credential a hosted endpoint answers 401,
  and a health probe that only asks "did anything answer" reports the server as down. `codezaiku
  doctor` sends the key, so run it before concluding the endpoint is unreachable.

Environment beats any config file, so a host spawning CodeZaiku can set the credential per
invocation without touching the box. `codezaiku config list` shows whether a key is configured and
where it came from, but never prints it.

## Hosted models, measured (October 2026)

**The results, the minutes and the cost a job here were measured in October 2026, at the prices read on 16 and 17 October
2026**: OpenRouter's public model list, Anthropic's pricing page, OpenAI's through OpenRouter. **The prices in the `model add`
lines below were read again on 26 November 2026**, from OpenRouter's public model list. Three of them had moved a good deal in
six weeks, so read the list before you count on a figure, and keep your own current with
`codezaiku model set <name> --prices in/out/cache`; `codezaiku spend` reports at the prices you gave it. Through OpenRouter each
provider that serves a model sets its own price, and the list shows one of them; a job's record names the providers that
served it.

The picks, from the two batteries below:

- **Cheapest that is right: GLM-5.3 through OpenRouter.** 34 of 40 on the hard set, 17 of 18 on maintenance, about seven minutes
  and eight cents a job. `codezaiku model add glm openrouter z-ai/glm-5.3 --prices 0.07/1.40/0.069`
- **The careful one: GPT-6.1 Sol through OpenAI.** 38 of 40 and 18 of 18, ten minutes and 77 cents a job; Kimi K3 through
  OpenRouter is one run behind at 43 cents. `codezaiku model add sol openai gpt-6.1-sol --prices 2/10/0.10` and
  `codezaiku model add kimi openrouter moonshotai/kimi-k3 --prices 0.192/16.00/0.182`
- **Ordinary work at almost nothing: DeepSeek V4.1 Flash through OpenRouter.** 30 of 40 and 17 of 18, two cents a job; it misses
  most of the tasks that need ML judgement and often reaches the turn cap still verifying.
  `codezaiku model add deepseek openrouter deepseek/deepseek-v4.1-flash --prices 0.14/0.42/0.0042`
- **Free: the local Qwen3.8 27B** on one 24 GB card: 28 of 30 on the ordinary tasks of the hard set and 16 of 18 on maintenance, 1 of the ten that need ML judgement, at two to three times the minutes. Best judgement gives it every
  job it clears.
- **Claude Sonnet 5.5** is 34 of 40 and 18 of 18 at 84 cents a job, and the one model that could not judge how many topics a mailbox
  holds: fifteen to thirty-three on every run where seven were seeded.

The runs: two batteries on one FastAPI project of about thirty files, every run from a fresh copy, graded by a held-out test the
model never sees plus the project's twelve tests and a boot check. Maintenance: six tasks (a bug, a security hole, a feature, test
coverage, an incident, a migration), three runs each. The hard set: eight tasks (a classifier judged on hidden emails, a forecast
within 4 %, deduplication, threading, a date bug, a migration with data, clustering that must compare two mechanisms and find seven
seeded topics, a forecast with two cycles, a level shift and missing months), five runs each. Minutes and costs are the hard set's
where a model ran it, the maintenance battery's otherwise. The record, with every task, grader and defect found along the way, is
`bench/CODING_MODELS_2026-10.md` in the source tree.

| model, host | maintenance of 18 | hard set of 40 | minutes a job | cost a job, at October's prices |
|---|---|---|---|---|
| GPT-6.1 Sol, OpenAI | 18 | 38 | 10 | $0.77 |
| Kimi K3, OpenRouter | 18 | 37 | 8.5 | $0.43 |
| GLM-5.3, OpenRouter | 17 | 34 | 7 | $0.08 |
| Claude Sonnet 5.5, Anthropic | 18 | 34 | 7 | $0.84 |
| DeepSeek V4.1 Flash, OpenRouter | 17 | 30 | 8 | $0.02 |
| local Qwen3.8 27B, 4-bit, one card | 16 | 29 | 20 | free |
| Hy4 preview, OpenRouter (17 runs) | 15 | | 22 | $0.35 |
| Qwen3-Coder-Next, OpenRouter | 14 | | 2.4 | $0.03 |
| MiniMax M3, OpenRouter | 14 | | 2.7 | $0.06 |
| Nemotron 3 Ultra, OpenRouter | 13 | | 1.7 | $0.15 at the price of the day; no longer listed |
| MiMo-V2.6-Pro, OpenRouter (14 runs) | 11 | | 41 | $0.21 |

What the table says. On the ordinary tasks every model in the top six is at or near full marks, the local 27B included. What
separates them is the two tasks that need ML judgement, the clustering and the harder forecast, ten runs each: Sol 8, GLM 7, Kimi
7, Sonnet 5, DeepSeek 3, the local 27B 1. So: maintenance on the free model; anything with judgement in it on GLM-5.3 at
eight cents, or on Sol when it has to be right. The limits in [LIMITATIONS.md](LIMITATIONS.md) were measured on a local 9B; this
table is the 27B and the hosted models.

### A second local model: Inkling 2 Mini (measured November 2026)

Inkling 2 Mini (Thinking Machines Lab, open weights, 26B; `Inkling-2-Mini-UD-Q4_K_XL.gguf`, 17.6 GB; it needs llama.cpp build
12611 or newer) against Qwen3.8-27B on the same two batteries: the same 48 GB card, the same CodeZaiku (0.3.19), the same
llama.cpp, measured on 24 and 25 November 2026. We served it with llama.cpp's `--jinja`, its makers' sampling (temperature 1.0,
nothing cut off) and its thinking effort set to low.

| | Inkling 2 Mini | Qwen3.8-27B |
|---|---|---|
| maintenance of 18 | 18 | 18 |
| hard set of 40 | 28 | 26 |
| hard-set runs that reached the 60-turn limit | 0 | 7 |
| minutes a hard-set job | 5.4 | 15.1 |
| writing speed | 50 tokens a second | 35 tokens a second |

The two are equal within what five runs a task can tell apart (the table above has the 27B's October figures, 16 and 29, on an
older CodeZaiku), and Inkling gets through a job about three times faster, because it finishes in fewer turns. They fail on
different tasks: Inkling passed the clustering task 4 times of 5 where the 27B passed none, and the 27B passed the two forecasts
5 times of 10 where Inkling passed 1. On a 48 GB card it is a reasonable local model for coding. `model serve install` still
installs the 27B; to try Inkling, serve it yourself (see "Running a server") and point `CODEZAIKU_DRIVE` at it.

## Saved models, chosen per job

One machine often has several models within reach: the server on its own card, a cheap fast one through a router, a careful
one for the task that matters. A saved model keeps one under a name, and a job picks one by that name without touching the
settings every other job reads.

```bash
codezaiku model add local-27b http://localhost:8200                              # the server on this machine
codezaiku model add deepseek openrouter deepseek/deepseek-v4.1-flash --prices 0.14/0.42/0.0042 --cap 20
codezaiku model add fable anthropic claude-fable-5-1 --prices 5/25/0.5 --effort high --note "the careful one"
codezaiku model add sol openai gpt-6.1-sol --prices 1.5/12/0.15
codezaiku model key openrouter --file ~/openrouter-key.txt    # kept for openrouter.ai, sent there and nowhere else
codezaiku model key fable --file ~/claude-key.txt             # a saved model's name works too: its host gets the key
codezaiku model test deepseek                                 # one word back, and what the call cost
codezaiku model list
```

The address is an `http(s)://` base or a host word: `anthropic`, `openai`, `openrouter`, `gemini`, `nebius`, `huggingface`,
`deepseek`, `moonshot`. The model id is the one the server selects by (a local llama.cpp ignores it; leave it out). Prices are
dollars per million tokens, `in/out` or `in/out/cache` where the host charges less for the part of the prompt it read from its
cache; they are what you write down, and `codezaiku spend` does arithmetic on them. The effort is `on` or `off` for the thinking switch through OpenRouter (measured 17 October 2026: an effort level there is not more thinking than the default, `on` is), or the model's own levels
(`low`, `medium`, `high` …) and wins over `CODEZAIKU_REASONING_EFFORT` for that model. The cap is dollars a month: once the month's
jobs on that saved model add up to it, the next one is refused before it starts, with the figures.

Then, per job:

```bash
codezaiku code ~/proj "add a --json flag to the exporter" --model deepseek
codezaiku review ~/proj HEAD~1 --model fable
codezaiku research "which Rust crate parses EXIF without libexif?" --model sol
codezaiku chat ~/proj --model local-27b          # and /model fable inside the chat, for the rest of the session
codezaiku run --text "…" --model deepseek         # the result document carries savedModel, host, usage and cost
```

Over MCP the `code`, `review` and `research` tools take `model`; over ACP the session's model option lists the saved models
beside the drive's own. A `--model` that is not a saved name is taken as a model id on the drive, as before. `codezaiku model use
<name>` makes a saved model the default for every job; the drive and model settings are the saved model called `default`, and
a machine with nothing saved works exactly as it did.

## Best judgement: `auto`

With several models saved, naming one for every job is a chore, and the right one depends on the job: a small change that can wait
is work for the free local model, a migration somebody is waiting on is work for a fast capable one. `--model auto` makes the choice
and says why:

```
auto chose glm: tier A for a tier-A code job, about 3 minutes, about $0.03; sonnet about $0.70, dearer or slower
auto chose local-27b: tier B for a tier-B code job, about 9 minutes, free; glm about $0.03, dearer or slower; sonnet about $0.70, dearer or slower
```

The first is a migration somebody is waiting on; the second a small change run with `--wait 0`.

The rule is plain. Among the saved models that have a key where one is needed and are under their monthly cap, those below the tier
the job needs are dropped; when somebody is waiting, those slower than the wait you accept are dropped; the cheapest of the rest is
taken. A free local model wins whenever it clears the tier and the wait. If nothing clears the wait, the wait is relaxed and the pick
says so; the tier is never lowered below the best that is available.

Each saved model has a tier, A, B or C: a measured default for its family (from the batteries of October 2026 above: the Claude line,
Kimi K3, GPT-6.1 Sol and GLM-5.3 are A; DeepSeek V4.1 Flash and the local Qwen3.8 27B are B, since both miss the tasks that need
ML judgement), or one you set, `codezaiku model set
mimo --tier B`. The tier a coding job needs comes from its goal: a build from nothing, a migration, security, a refactor or anything
with machine learning in it needs A; ordinary maintenance B; a typo or wording change C. A review needs B, a research run A.

**A job nobody is waiting for runs on the free twin when there is one.** OpenRouter lists some models twice, the
second as `<id>:free`, the same weights rate-limited at no charge; a batch job on such a model takes the twin, the pick
says so, and the ledger prices it at nothing. A job somebody is waiting for keeps the paid one. A `run` job says nobody is
waiting with `--wait 0`.

**How hard a model thinks is set on the saved model.** `--effort on|off` on a saved model turns its thinking on or off through
OpenRouter (GLM, Kimi and DeepSeek have the switch); a run starts at that setting and keeps it.

Minutes and dollars are estimated from the saved model's prices and typical tokens at first; once a model has run three jobs of a
kind here, from the ledger's own record of what it took and cost, which beats any list price.

Once a second model is saved, auto is the default whenever no model is named; `codezaiku config set auto off` restores the old
behaviour, and `--model <name>` always pins a job. The knobs: `auto.wait_minutes` (5) is how long you accept waiting for a job you
are waiting for, `--wait 0` on a job means as long as it takes; `auto.max_dollars` caps what one job may be estimated to cost;
`auto.local_only` keeps every job on local models; and `codezaiku model private <project> on` marks one project so that only local
models may read it, for code that must not leave the machine. `codezaiku model auto --kind code --tier A --project <dir>` shows the
pick for such a job without running anything.

**The overseer.** `--overseer <saved name>` beside `--model` has a stronger model look over the cheaper model's work: once when
it finishes, and once mid-run if the check has stayed red for most of the turns. It reads the task, the plan, the changed files and
the check's verdict, and names the one thing wrong or missing, which the worker then settles. A few short calls a job, shown on the
cost line. It is off unless you ask for it: measured on 18 October 2026 with the local 27B working and GLM-5.3 overseeing, it did
not lift the local model on hard work (15 of 24 against 29 of 40 alone, and 5 of 12 against 9 of 20 in a second shape), and a
plan the stronger model wrote made a long task worse, so the worker keeps its own plan. `codezaiku config set auto.overseer on`
makes auto pair one. For hard work, the lever is which model does the work, which auto's tier choice already decides.

## A fallback for when the model is busy or off

```bash
codezaiku model add homebox http://homebox:8210 qwen3.8-27b    # the bigger card, on another machine
codezaiku model add local http://127.0.0.1:8211 gpt-oss-20b     # this machine's own
codezaiku model use homebox
codezaiku config set fallback local
```

With a fallback set, a job asks the model it would run on two short questions before it starts: does it answer (its model list,
within a few seconds), and is it busy. Busy means a llama.cpp server with every one of its slots working on a request. Behind a
llama-swap proxy only a model the proxy already has loaded is asked, so the question never starts a model, and a model that is not
loaded counts as free. A server that shows no slots is not busy, and a hosted API is not asked. When the model is off or busy and
the fallback answers, the job runs on the fallback, and one line before the work says so:

```
homebox does not answer, so this job runs on local (gpt-oss-20b).
homebox is busy (4 of 4 calls in use), so this job runs on local (gpt-oss-20b).
```

The fallback may be a weaker model than the one the job would have had. The line, the job's cost line and the result document of
`codezaiku run` name the one that ran. When the model does not answer and the fallback is busy, the job goes to the fallback and
waits there for a free call; when both are busy, or both are off, it stays where it was and the line says both were tried. The check
is made at the start of a `code`, `run`, `review`, `research` or `decompose` job and of a chat (`/model` inside a chat is your own
choice). With auto on, best judgement picks among the saved models first and the fallback stands behind its pick; to keep jobs on
the default and use the fallback only when it is busy or off, `codezaiku config set auto off`. `codezaiku model list` shows the
fallback beside the model it stands behind, and `codezaiku config unset fallback` turns it off. With no fallback set, nothing is
asked.

## What a job cost

Every job counts the tokens the server reports — prompt, read from the cache, completion — and prices them when the saved
model has prices. The line is printed when the job ends (`model: deepseek (deepseek/deepseek-v4.1-flash via openrouter.ai) —
412,300 prompt (361,000 from the cache) + 9,800 completion tokens, $0.04`), the chat's `/cost` shows the session's, and each
job adds a line to `~/.codezaiku/spend.jsonl`. `codezaiku spend` adds them up by host and by saved model, this month and in all.
The figures are CodeZaiku's arithmetic at the prices you wrote down, not the host's bill: a token the Claude API writes to its
cache costs a quarter more than the input price, which they leave out.

A reply the chat streams (`CODEZAIKU_STREAM`) is counted the same way: the stream asks the server for its token counts
(`stream_options.include_usage`), and they go into `/cost`, the ledger and a saved model's monthly cap. When a model server
sends no counts with an answer, nothing is counted for it and nothing is guessed: `/cost` says how many answers came without
counts, and the ledger line keeps the number under `unreported`.

Through OpenRouter, every request asks OpenRouter to say which provider served it, and the job keeps the count. The line at the
end names them (`… $0.04 — served by DeepInfra (7 requests), Fireworks (1 request)`), the chat's `/cost` names them for the model in
use, and the job's line in `spend.jsonl` keeps them under `served`. Each call's line in the log names its provider too.

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

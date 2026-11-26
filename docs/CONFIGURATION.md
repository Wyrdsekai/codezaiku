# Configuration

Settings live in a **config file**; environment variables override it. There are about sixty knobs;
**a handful matter** for normal use and the rest are per-surface switches or experiment flags.

```bash
codezaiku init                                    # write a starter config
codezaiku config list                             # what is set, and where it came from
codezaiku config set drive http://localhost:8200  # change a setting
codezaiku config set librarian.token --file signin.txt   # a secret from a file, kept out of the shell's history (or - to type it unseen)
codezaiku config get ops.authority
codezaiku config unset searxng
codezaiku config edit                             # open it in $EDITOR
codezaiku config path
codezaiku doctor                                  # what is missing, and the fix
```

`config set` edits in place, so comments and ordering survive. If a setting is also exported in your
environment — which overrides the file — it says so rather than letting the change look like it did
nothing. A value typed on the command line stays in your shell's history: for a key or a sign-in,
`--file <path>` reads it from a file (its last line that is not empty) and `-` asks for it without
showing it, or reads it from a pipe. `config set` and `config list` print a key or a sign-in as `(set)`.
A word that begins with two dashes is never taken for a value: `config set` refuses it and stores nothing.
A value that really begins with two dashes goes after `--`: `codezaiku config set <key> -- --its-value`.

### Where settings come from

First hit wins:

1. **the environment variable** — an explicit export always wins, so scripts and CI keep working
2. **`$CODEZAIKU_CONFIG`**, or `~/.codezaiku/config`
3. **`/etc/codezaiku/config`** — the system-wide file, for a packaged install

### File format

`key = value`, `#` comments, blank lines ignored. Keys may be written three ways and all resolve to the
same setting, so you do not have to remember which:

```ini
drive                     = http://localhost:8200
ops.authority             = propose
CODEZAIKU_OPS_ROLLBACK    = on
```

---

## The ones that matter

**Precedence, highest first:** an environment variable, then this machine's config file, then
`<KEY>_DEFAULT` from the environment, then the built-in default.

That third level exists for hosts. An environment variable carries two different intentions — "the
operator chose this" and "I am filling in a blank" — and CodeZaiku cannot tell them apart. A host
that sends its own built-in default as `CODEZAIKU_DRIVE` silently overrides a machine that *was*
configured, and the mismatch is invisible: the config file still reads correctly, so `codezaiku
doctor` reports healthy while a spawned run goes somewhere else. Sending `CODEZAIKU_DRIVE_DEFAULT`
instead offers a value rather than imposing one, and `codezaiku config list` shows which level each
setting came from.

Two details that matter if you are wiring this up:

- **A present-but-blank value counts as unset**, at every level. Exporting `CODEZAIKU_DRIVE=` does
  not blank out a configured endpoint; resolution simply falls through to the next level. So a host
  that has nothing to say should say nothing rather than send an empty string.
- **Each setting resolves on its own.** A machine that configured only the endpoint takes its own
  endpoint and the host's model, and `config list` labels them separately. Levels mix per key rather
  than applying as a block.

| Variable | Default | What it does |
|---|---|---|
| `CODEZAIKU_DRIVE` | `http://localhost:8200` | The OpenAI-compatible model server. The only genuinely required setting. Give the base address; an address pasted with `/v1` on the end is accepted too. |
| `CODEZAIKU_MODEL` | `local-model` | The `model` field sent with each request. llama.cpp ignores it and serves whatever is loaded, but Ollama, LM Studio, vLLM and hosted APIs use it to **select** a model — set it there or requests fail. |
| `CODEZAIKU_SHELL` | Git Bash on Windows, `bash` elsewhere | The shell every command is dispatched through. Worth knowing about on Windows, where asking for `bash` does not get you the one on your PATH: `C:\Windows\System32\bash.exe` is the **WSL launcher**, and `CreateProcess` finds `System32` before any PATH entry — so a plain `bash` runs your commands inside the Linux distribution instead. CodeZaiku resolves Git Bash by absolute path to make that a choice rather than an accident. Set this to dispatch elsewhere on purpose. `codezaiku doctor` prints the shell it resolved. |
| `CODEZAIKU_SHELL_TIMEOUT_SEC` | `300` | Per-command wall-clock cap for the model's shell. Raise it when a task legitimately runs for minutes — a step making hundreds of model calls, a full-dataset pass. A command killed on this cap is told that the cap expired and that it may simply need longer, so it does not respond by wrapping itself in a shorter `timeout`. |
| `CODEZAIKU_SHELL_HEAVY_TIMEOUT_SEC` | `1200` | The same cap for commands recognised as training or generation steps. Recognition is by name (`train.py`, `finetune`, `torchrun`, …), so a long-running script called something else gets the ordinary cap — set the value above rather than relying on the guess. |
| `CODEZAIKU_API_KEY` | unset | Credential for the model endpoint, sent as `Authorization: Bearer <key>` on every request including the health probe (the Claude API gets it in its own `x-api-key` header). Needed by most hosted providers; leave unset for a local llama.cpp or Ollama, which want no header. Accepts the key bare or already prefixed with `Bearer `. `codezaiku config list` reports whether it is set, never its value. |
| `CODEZAIKU_SARIF` | unset | Path to write findings as **SARIF 2.1.0**. Off unless set. Upload it to GitHub code scanning and findings appear on the pull request; DefectDojo, the VS Code SARIF viewer and most vulnerability managers ingest the same file. Unanchored findings are written without a line region rather than at a guessed line. |
| `CODEZAIKU_REVIEW_PASSES` | `1` | How many independent passes `review` makes before merging findings. Raising it trades time for recall by unioning repeated passes: roughly a third of PRs yield findings on some runs and not others. It also multiplies false positives and costs N× the model time — hence off by default. Capped at 5. See LIMITATIONS.md for the measured figures. |
| `CODEZAIKU_SEARXNG` | `http://localhost:8888` | Search backend for the research surface. Needs `json` in its `search.formats` — it is off by default in SearXNG. `doctor` and `setup` check it without searching: a search with no words, which SearXNG answers with "No query" in JSON when its JSON output is on, and its `/config`, for its version and the engines a plain search uses. No engine is asked, and no key is sent. |
| `CODEZAIKU_OPS_AUTHORITY` | `propose` | Highest rung the operator may act at: `observe` < `localize` < `propose` < `guarded` < `unattended`. Start at `propose`. |
| `CODEZAIKU_OPS_HALT` | unset | Set to `on` to force everything to `observe`. The kill switch. |
| `CODEZAIKU_OPS_ROLLBACK` | `on` | Snapshot before a destructive fix and restore if verification fails. Leave it on. |
| `CODEZAIKU_OPS_AUDIT` | unset | Path for the JSON-lines audit trail. Set it if anything acts on your systems. |

A working minimum:

```bash
export CODEZAIKU_DRIVE=http://localhost:8200
export CODEZAIKU_OPS_AUDIT=$HOME/.codezaiku/audit.jsonl
```

---

## Operations

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_OPS_APP_HEALTH` | auto-discovered | The app's per-dependency readiness endpoint. **This is the load-bearing signal** — it names the failing dependency, including degraded-but-container-healthy faults. Auto-discovery prefers `/ready` over `/health`. **It must return `{"deps": {"<name>": "ok"}}`** — any dependency whose value is not exactly `"ok"` counts as unhealthy, and the before/after difference across a fix is the harm metric. A response without an object `deps` field gives the harm metric no input at all; CodeZaiku logs a warning rather than treating it as healthy. Spring Boot's actuator shape (`components`) is **not** read — expose a small endpoint in this shape instead. |
| `CODEZAIKU_OPS_STACK_LOCALIZE` | from scope | Compose project to sense. Usually comes from the `fix <scope>` argument. |
| `CODEZAIKU_OPS_VERIFY_CMD` | derived | Command whose exit status decides whether a fix worked. Derived from the health endpoint if unset. |
| `CODEZAIKU_OPS_KNOWLEDGE` | auto | Directory of fix cards. Resolved in order: this variable, `$CODEZAIKU_HOME/ops-knowledge`, the directory beside an install, then `./ops-knowledge`. Set it only to point somewhere unusual. |
| `CODEZAIKU_KNOWLEDGE_PACKS` | auto | Same resolution, for the evergreen knowledge packs. |
| `CODEZAIKU_OPS_LEARN` | unset | Directory for cards learned from successful recon. Enables learn-back and auto-promotion. |
| `CODEZAIKU_OPS_PROMOTE_N` | `3` | Verified reuses before a learned card is promoted from candidate to validated. |
| `CODEZAIKU_OPS_RECON` | on | Derive a fix read-only when no card matches. |
| `CODEZAIKU_OPS_PRECHECK` | on | Deterministic checks before the model spends a turn. Set `off` to measure raw model diagnosis. |
| `CODEZAIKU_OPS_ALERT_WEBHOOK` | unset | POST target for harm and rollback events. |
| `CODEZAIKU_OPS_HALT_FILE` | unset | Path whose existence forces `observe`. Useful for external control. |
| `CODEZAIKU_OPS_REMEDIATE` | unset | `on` raises the default ceiling from `localize` to `guarded` for surfaces that take no explicit ceiling argument. Prefer passing the ceiling per command. |
| `CODEZAIKU_OPS_COMPOSE_PROJECT` | auto | Compose project name, when it cannot be derived from the scope. |
| `CODEZAIKU_OPS_RUNBOOK` | unset | Runbook listing mode for the operator's prompt. |
| `CODEZAIKU_OPS_CHANGES` | on | Recent-change sensing (a fault that appeared right after a deploy is usually the deploy). `off` disables it. |
| `CODEZAIKU_OPS_TURN_FEEDBACK` | unset | `on` adds per-turn probe feedback during diagnosis. |
| `CODEZAIKU_LOCALIZER_MODEL` | falls back to `CODEZAIKU_DISTILLER_MODEL` | Model name for the localization hand-off. |
| `CODEZAIKU_WIRING_MODEL` | falls back to `CODEZAIKU_DISTILLER_MODEL` | Model name for the wiring step. |

## Security

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_OPS_SECURITY` | on | Posture and intrusion reporting on the `watch` loop. |
| `CODEZAIKU_OPS_FALCO_ALERTS` | `/var/log/falco/alerts.json` | Falco JSON alert stream. Without it, intrusion detection reports *"nothing is watching"* — never "no intrusion". |
| `CODEZAIKU_OPS_FALCO_MIN_PRIORITY` | `WARNING` | Severity floor for reported detections, applied to whichever sensor is active. |
| `CODEZAIKU_OPS_WAZUH_ALERTS` | `/var/ossec/logs/alerts/alerts.json` | Wazuh's JSON alert stream — the fallback detector where Falco cannot run, macOS above all. Falco is preferred when both are present because it sees syscalls. **Wazuh is not an equivalent**: on macOS it analyses the unified log, file integrity and polled command output, so it observes what was *logged*, not what the kernel saw. `secure` names the active sensor and its reach rather than reporting a bare "none detected". Verified against a live wazuh-manager 4.9.2. |
| `CODEZAIKU_OPS_TLS_ENDPOINTS` | unset | Comma-separated `host:port` list for certificate-expiry checks. Opt-in on purpose: a proactive scan must never surprise-probe a host you did not name. |

## Proactive maintenance

| Variable | Default |
|---|---|
| `CODEZAIKU_OPS_DISK_WARN_PCT` | `85` |
| `CODEZAIKU_OPS_MEM_WARN_PCT` | `90` |
| `CODEZAIKU_OPS_CERT_WARN_DAYS` | `14` |
| `CODEZAIKU_OPS_HORIZON_HOURS` | `48` — how far ahead a trend must project to be reported |

## Research

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_RESEARCH_POOL` | `~/.codezaiku/research/findings.jsonl` | Accumulated findings. **Use a separate pool per experiment arm** — a shared pool carries one arm's findings into another and invalidates the comparison. |
| `CODEZAIKU_LIBRARIAN_URL` | `http://127.0.0.1:4649` | Where ResearchZosho's library answers, on this computer or another one on your network (`http://192.168.1.20:4649`; that library must listen on its network address: `researchzosho service install --host 0.0.0.0` there). The chat pushes what the library holds into each turn, `/librarian` asks it, `/research go` files runs with it. Without a library, none of that happens and the research memory is what you have. `codezaiku doctor` says what the library at this address is to CodeZaiku, whether or not ResearchZosho is installed here. `CODEZAIKU_LIBRARIAN_PORT` changes only the port. |
| `CODEZAIKU_LIBRARIAN_TOKEN` | — | CodeZaiku's token for the daemon, sent with every request to it: needed to file research runs from the chat, and to read at all when the library refuses anonymous readers (`researchzosho reader default deny`). `codezaiku install researchzosho` makes one, and `codezaiku install researchzosho --token` makes a new one. For a library on another computer, run there `researchzosho reader allow did:key:local-codezaiku write codezaiku` and `researchzosho reader token did:key:local-codezaiku > codezaiku-signin.txt`, copy the file here, run `codezaiku config set CODEZAIKU_LIBRARIAN_TOKEN --file codezaiku-signin.txt` and delete the file: the token is then on no screen and in no shell's history. A library is found by its health address, which needs no token; `doctor` and the chat then say whether it lets CodeZaiku in, and if not, these commands. |
| `CODEZAIKU_UPDATE` | `check` | `check`: `doctor` and the chat say when a newer CodeZaiku is released. To know, they ask GitHub's API for CodeZaiku's latest release, at most once a day: one request to `api.github.com`, which carries CodeZaiku's version as its client name (`CodeZaiku/<version>`) and nothing else, sent when `doctor` runs or a chat starts (in the web app, when a project's chat page opens); the answer is kept in `~/.codezaiku/codezaiku-latest.txt`. Where ResearchZosho is installed (its `researchzosho` command is on the PATH or where its installer puts it), they ask for its latest release the same way. `auto`: the same requests, and a chat swaps a newer release in at its start, for the next start; the web app, left running, asks once a day at a quiet moment and does the same (tarball installs on Linux and macOS; a Debian install uses apt). The new release is downloaded from GitHub with its `SHA256SUMS`. `off`: CodeZaiku asks GitHub nothing by itself, and `doctor` and the chat say nothing about new releases; `codezaiku config set update off` sets it. In every mode, `codezaiku update` asks GitHub for the latest release when you run it, and `codezaiku update now` downloads it. This setting is CodeZaiku's alone: ResearchZosho has its own, `RESEARCHZOSHO_UPDATE`, and its own service updates it. |
| `CODEZAIKU_GAPREFLECT` | on | Gap-reflection loop. Measured *unproven* (see LIMITATIONS.md); left on because it shows no harm. |

## Driving CodeZaiku from another program

| Variable | Default | What it does |
|---|---|---|
| `CODEZAIKU_RUN_MAX_TURNS` | `40` | Turn budget for `codezaiku run`. CodeZaiku bounds a run by turns, not wallclock — if your host enforces a time limit, hard-kill it and read the result document it emits on the way out. |
| `CODEZAIKU_ACP_PERMISSION_TIMEOUT` | `600` | Seconds `codezaiku acp` waits for a `session/request_permission` answer. Generous because the far side may be asking a human. **An unanswered request refuses** — a gate that proceeds because nobody replied has not gated anything. |

See **[DEPLOYING_AS_A_BACKEND.md](DEPLOYING_AS_A_BACKEND.md)** for the full contract.

## Coding

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_SELFVERIFY` | on | Ask the model to run its own deliverable end-to-end before finishing. |
| `CODEZAIKU_BOOTGATE` | on | Harness-owned end-to-end boot of an assembled HTTP app. |
| `CODEZAIKU_PROJECT_MEMORY` | on | Per-project conventions loaded into context. |
| `CODEZAIKU_DISTILLER_URL` | unset | Larger model for one-shot localization hand-offs. Optional. |
| `CODEZAIKU_DISTILLER_MODEL` | `default` | The `model` field sent to that endpoint. |
| `CODEZAIKU_READ_CAP` | `12000` | Chars a single `read_file` may return. Above it the tool returns the file's first lines for structure and asks the model to `grep -n` then read a range. **Measured, not guessed**: against a 30k cap this was ~24% cheaper in tokens at identical task success, because a large file enters the conversation once and is then re-sent on every later turn. See LIMITATIONS.md. |
| `CODEZAIKU_FAMILIAR_MEMORY_DIR` | `~/.codezaiku/familiar-memory` | Where per-project conventions are kept. |
| `CODEZAIKU_OCEAN_DIR` | auto | Location of the knowledge library's search index (Lucene, words only; no embedding model). |
| `CODEZAIKU_GIT_PUSH` | off | What `code`, `run`, MCP and ACP jobs may do with the project's remotes (over ACP the client is asked as well, each time). `off`: no git tools. `read`: fetch and read pull requests. A list of repositories, `host/owner/name` with `*` for the owner or the name: also push to them and open pull requests there, never a force push: `codezaiku config set git.push "github.com/me/proj, github.com/me/*"`. The chat asks you every time whatever this says. See "Push, fetch and pull requests" in [SECURITY.md](../SECURITY.md). |

## Sandbox

The commands a coding job runs (the shell tool, the project's tests and checks, git, language servers, a weights
workspace's Python) run in an operating-system sandbox: Landlock on Linux 5.13 and later, `sandbox-exec` on macOS.
A command reads and writes the project folder and the job's own folders, reads the system's folders and the
toolchains found on `PATH`, and gets "permission denied" for the rest of the machine's files, your home folder
included. `codezaiku doctor` says which sandbox the machine has, and every job says it in its first lines.

Each project gets a home, a temp folder and package caches of its own under `~/.codezaiku/envs/`, so `~/.gradle`,
`~/.m2`, `~/.npm`, pip's and cargo's caches are not read. The first build of a project therefore downloads its
build tool and dependencies again (a Gradle wrapper build with JUnit: 167 MB and 9 seconds on a fast line; 1 second
after that). Set `CODEZAIKU_SANDBOX_CACHE` to share one cache folder between projects.

Variables whose names look like credentials (`*_KEY`, `*_TOKEN`, `*_SECRET`, `*PASSWORD*`, cloud credentials) or
whose values look like one are not passed to a sandboxed command; nor are the addresses of your session's services
(the SSH agent, the session bus, `DOCKER_HOST`) or CodeZaiku's own settings. Of your git settings a command gets your
name and address (`user.name`, `user.email`, the project's own first), so `git commit` works, also in git's
`GIT_AUTHOR_*` and `GIT_COMMITTER_*` variables, so a command's own `-c user.name=…` does not change who a commit is by;
nothing else of them. No credential helper is asked from inside, the system's included (on a Mac, the keychain's, which
Apple's git names): a command has no credential to give or to get. Pushing, fetching and pull requests are done by
CodeZaiku itself, with your own settings and credentials: see `CODEZAIKU_GIT_PUSH`.

Where the machine has no sandbox (Windows outside WSL2, a kernel without Landlock, `CODEZAIKU_SANDBOX=off`), a job
on a local model runs its commands unsandboxed and says so; a job on a hosted model runs no commands: the shell and
the other tools that run a program are not offered, and the first lines say why and what to do. On Windows, run
CodeZaiku inside WSL2 to have the sandbox.

| Variable | Default | Notes |
|---|---|---|
| `CODEZAIKU_SANDBOX` | on | `off` runs commands unsandboxed. A job on a hosted model then runs none. |
| `CODEZAIKU_SANDBOX_CACHE` | unset | A folder for the package caches of every project's sandbox (Gradle, Maven, npm, yarn, pip, cargo, Go modules). Unset, each project keeps its own. |
| `CODEZAIKU_SANDBOX_ENV` | unset | Names of variables to pass although they look like credentials, comma-separated: `NPM_TOKEN,GH_TOKEN`. |
| `CODEZAIKU_SANDBOX_READ` | unset | More folders a command may read, comma-separated: a toolchain the rules did not find, a data folder. `/proc` here opens the machine's process list, which `ps`, a program built with the address sanitizer and Node's `process.memoryUsage()` need. |
| `CODEZAIKU_SANDBOX_WRITE` | unset | More folders a command may read and write. |
| `CODEZAIKU_SANDBOX_SOCKETS` | closed | Linux. Closed, a command cannot create a UNIX-domain socket, which keeps it from the Docker socket, the desktop session's bus and anything else that listens on a socket file. Python 3.14's process pools and `multiprocessing.Manager` need one: `open` allows them, and those services are then reachable. On macOS a command may use socket files inside its own folders only. |
| `CODEZAIKU_SANDBOX_NETWORK` | auto | Linux and macOS. `auto`: on a hosted model a command has no network of its own and reaches the package registries and the hosts you named through CodeZaiku, which writes down every try; on a local model the network is as before. `closed` and `open` do one or the other whatever the model. On macOS a closed network still reaches this Mac's own services. See "A job's commands and the network" in [SECURITY.md](../SECURITY.md). |
| `CODEZAIKU_SANDBOX_HOSTS` | unset | Hosts a command may reach where its network is closed, comma-separated: `github.com`, `api.example.org:8443`, `*.example.net`. A host alone means ports 443 and 80. In the chat a yes to the question about a refused host lets it in until the chat ends. |
| `CODEZAIKU_SANDBOX_LISTEN` | open | macOS, where a command's network is closed. `open`: a command may start a server, so a project's tests that start one work, and while it listens on every address it can be reached from your network. `closed`: a command may not listen for connections, which shuts that door; a test that starts a server and a Gradle build then fail. On Linux a server a command starts is reached only by the project's other commands, whatever this says. |

What the sandbox does not limit: the network on a local model (a command can reach the internet and services on this
machine's ports; `sandbox.network closed` closes it), this Mac's own services on macOS even with the network closed, and
the names of files outside the project on Linux (a command can test whether a path exists, not list a folder or read a
file). See [LIMITATIONS.md](LIMITATIONS.md).

## Remote and container targets

| Variable | Notes |
|---|---|
| `CODEZAIKU_SSH_PASSWORD` | Only if key-based auth is unavailable. Prefer keys. |
| `CODEZAIKU_EXEC_CONTAINER` / `CODEZAIKU_EXEC_WORKDIR` | Pin execution to a container and working directory. |

---

## Chat, research and hosted drives (0.2.0)

| key | what it does |
|---|---|
| `CODEZAIKU_CHAT_MODE` | Default consent mode for `chat`: `plan` · `ask` (default) · `auto-edit` · `yolo`. |
| `CODEZAIKU_CHAT_MAX_TURNS` | Loop-turn cap per chat turn for unattended runs; `0` = unlimited (the default — interactively, YOU are the cap). Either way, a turn ends when the model has answered three times in a row in words without calling a tool, and the chat shows what it said: CodeZaiku asks for a tool call, and a model server that does not hold the model to that would otherwise be asked again and again. |
| `CODEZAIKU_CHAT_DIR` | Where sessions, project memory and the undo journal live (default `~/.codezaiku/chat`). |
| `CODEZAIKU_UNDO_DEPTH` | How many agent steps `/undo` can walk back (default 10, or the turn cap if higher). |
| `CODEZAIKU_SEARXNG` | SearXNG endpoint for `web_search`. Setting it (or the Brave key) is what puts web tools into chat. |
| `CODEZAIKU_SERPER_KEY` | Serper key (serper.dev): Google's own results, used first for web search when set. Paid per search after the free credits, about a tenth of a cent each; a research run makes tens of searches. Google releases only the top three links of a search at once since 2026-10-30; the rest are asked for with Serper's free second call, and an address still unreleased is shown as a Google redirect with the site's readable name. |
| `CODEZAIKU_SERPAPI_KEY` | SerpApi key (serpapi.com): Google's own results, used first when set and no Serper key is. Paid per search after the free hundred a month, about one and a half cents each; most addresses come as Google's unreleased redirects today, so Serper is the better key. |
| `CODEZAIKU_FIRECRAWL_KEY` | Firecrawl key (firecrawl.dev): a page a plain fetch cannot read, built by scripts, a bot wall or a 403, is read through Firecrawl's browser instead and the source line says so. Paid per page after the free credits, at most twenty pages a run. |
| `CODEZAIKU_BRAVE_KEY` | Brave Search API key — the search backend after a Google key when one is set, before SearXNG; SearXNG is the fallback on any failure. A key given to `setup` is tried with one search for the word "test", which Brave counts against your plan; setup says so before it sends it. `doctor` only looks whether a key is set. |

Every search and every page read through a service is counted, by month and by service, with the credits the paid ones charge, in `search-tally.json` in the settings folder; `codezaiku doctor` says what the month cost so far ("This month, 2026-10: 123 searches through Serper, 123 credits; 40 through Brave; 12 pages through Firecrawl, 12 credits.").
| `CODEZAIKU_FALLBACK_SEARCH` | `on` — when neither Brave nor SearXNG answers, `web_search` uses Wikipedia plus Crossref and OpenAlex (reference pages and papers by DOI). `off` disables it. `scholar_search`, the literature by DOI, is offered in every research run regardless. |
| `CODEZAIKU_FETCH_PRIVATE` | `deny` refuses `web_fetch` of private-network addresses. Loopback, link-local, unspecified, multicast and the hosts of your own drive, embedder, reranker and search backend are always refused, at every redirect hop. |
| `CODEZAIKU_FETCH_MAX_BYTES` | The largest document `web_fetch` reads (default 25 MB); the body and its decompression are capped. |
| `CODEZAIKU_RESEARCH_WORKERS` / `_WORKER_TURNS` / `_ROUNDS` | Fan-out research: parallel sub-researchers (4), their turn budget (14), critic rounds (2). |
| `CODEZAIKU_DELEGATE_DRIVE` / `CODEZAIKU_DELEGATE_MODEL` | Sub-agents from `delegate` run HERE instead of the chat's drive — frontier judgment, local labor. |
| `CODEZAIKU_STREAM` | Unset: the chat shows the whole reply when it is ready (the default). `on`: the reply as it is written. `all`: also the model's thinking, dimmed. A streamed reply's tokens are counted as a whole reply's are. A reply that breaks off part-way is marked where it broke and asked for again whole. |
| `CODEZAIKU_MCP_SERVERS` | `name=command;name2=command2` — spawns MCP stdio servers whose tools join chat as `mcp_<server>_<tool>`, consent-gated per call. |
| `CODEZAIKU_MCP_LINKS` | The MCP servers, by name, whose answers' web addresses the model may open: `codezaiku config set mcp.links "search, docs"`. Unset, no server's are; see SECURITY.md, "Other MCP servers". |
| `CODEZAIKU_MCP_TIMEOUT_SECONDS` | How long a call to an MCP server waits with no answer and no progress notification. Default 60. |
| `CODEZAIKU_ACP_MCP_MAX_TOOLS` | How many tools the MCP servers an ACP client passes may add to a session. Default 40. |
| `CODEZAIKU_V1_PORT` | Port for `codezaiku v1` (default 7071). |
| `CODEZAIKU_WEB_PORT` | Port for `codezaiku web`, the chat in the browser (default 7072). |
| `CODEZAIKU_WEB_DIR` | Where `codezaiku web` keeps its project list and token (default `~/.codezaiku/web`). |
| `CODEZAIKU_WEB_TOKEN` | The token `codezaiku web` asks the browser for. On the network one is made and kept in `~/.codezaiku/web/token` when none is set. |
| `CODEZAIKU_TEMP` | Sampling temperature override; `none` omits the field (some hosted models reject any temperature). |
| `CODEZAIKU_DRIVE_TIMEOUT` | Per-request HTTP timeout in seconds (default 300). Raise for drives with very long generations. |
| `CODEZAIKU_DRIVE_TEMPLATE_KWARGS` | JSON object merged into every request's `chat_template_kwargs` — e.g. `{"enable_thinking":false}` for engines with no server-side reasoning control. |
| `CODEZAIKU_TOOL_CHOICE` | `auto` asks the model server for tool calls without forcing one. CodeZaiku switches to it by itself the first time a server refuses a forced tool call (Claude Opus 5.5, Fable 5.1 and Sonnet 5.5 do); setting it saves that one refused request. The Claude API (`https://api.anthropic.com`) is always asked without forcing. |
| `CODEZAIKU_RESEARCH_FIRST` | When the research pass before a coding task runs. `auto` (default): when the drive is not a frontier-class model (see `CODEZAIKU_DRIVE_CLASS`) and a search source answers — a Brave key, a SearXNG in the settings, or the ResearchZosho library. On an existing codebase the model's `QUESTIONS: none` is honoured. `always`: on every drive. `off`: never. |
| `CODEZAIKU_DRIVE_CLASS` | `frontier` or `small`. Overrides the family-name list that otherwise decides: `claude-*`, `gpt-5*`, `gpt-4.1*`, `o3*`, `o4*`, `gemini-2.5-pro`, `gemini-3*`, `grok-4*`, `deepseek-v3*`, `deepseek-r1`, `kimi-k2*`, `qwen3-max*`, `glm-5*` are frontier; a family's `mini`, `nano`, `flash`, `lite`, `small`, `tiny`, `distill` or `haiku` variant is small. The name is read the same way from a gateway (`openai/gpt-5`) or a local server (`GLM-5.3-big-IQ2_M.gguf`). The Claude API is frontier regardless of name. |
| `CODEZAIKU_RESEARCH_FIRST_TURNS` | Turns for the research pass before the work (default 8; half on an existing codebase). |
| `CODEZAIKU_CHECK_TIMEOUT_SEC` | How long the harness's own run of a task's declared check may take, in seconds (default 3600). It runs in the background. |
| `CODEZAIKU_REASONING_EFFORT` | How hard a thinking model thinks before each working reply, sent as `reasoning_effort`: the levels are the model's own (Qwen3.8: `low`, `medium`, `xhigh`; OpenAI-style APIs: `low`, `medium`, `high`). llama.cpp passes it to the model's chat template. Unset: the server's default. On the Claude API it is Claude's own effort setting: `low`, `medium`, `high`, `xhigh`, `max`. |
| `CODEZAIKU_THINKING_BUDGET` | The most tokens a model may think before each reply, for a server that takes such a limit (llama.cpp's `thinking_budget_tokens`). The thinking ends there and the reply goes on. Unset: no limit. |
| `model.<name>.url` / `.id` / `.prices` / `.effort` / `.cap` / `.note` | A saved model (0.3.13), written by `codezaiku model add <name> <address> [<id>] [--prices in/out[/cache]] [--effort e] [--cap dollars] [--note text]` and chosen per job with `--model <name>`. `url` is the address (a host word becomes one), `id` the model id the server selects by, `prices` dollars per million tokens, `effort` the model's own level, `cap` dollars a month after which a job on it is refused. The drive and model settings are the saved model called `default`. |
| `auto` / `auto.wait_minutes` / `auto.max_dollars` / `auto.local_only` | Best judgement (0.3.13). `auto` is on by default: with a second model saved, a job that names no model is given to the saved model that clears its tier and the wait at the lowest price, and the pick is printed with its reasoning. `auto.wait_minutes` (5) is the wait accepted for a job somebody is waiting for; `auto.max_dollars` caps a job's estimated cost; `auto.local_only` keeps every job on local models. A project with the file `.codezaiku/private` in it (`codezaiku model private <dir> on`) is read by local models only. `model.<name>.tier` (A, B, C) overrides the measured default for the model's family. |
| `fallback` | The saved model a job runs on when the one it would run on does not answer or is busy: `codezaiku config set fallback local`. Checked once at the start of a `code`, `run`, `review`, `research` or `decompose` job and of a chat, and only when this is set. Busy means a llama.cpp server with every slot working; behind llama-swap only a model already loaded is asked, so the check never starts one. A hosted API is not asked. The fallback may be a weaker model: a line before the work names the one that runs. Unset or `off`: no check. See [MODELS.md](MODELS.md). |
| `CODEZAIKU_MODEL_NETWORK` | Whether the model server `codezaiku model serve install` sets up on Linux can reach the network. Unset or `closed`: its container has no way out to the network, which the install tries before it finishes; where that does not hold on the machine, the install sets it up the earlier way and says why. `open`: the earlier way, for a model server that has to reach a network: `codezaiku config set model_network open`, then `codezaiku model serve install`. `codezaiku model serve status` says which way it is set up. ResearchZosho has the same setting under the same name. See "A model on your own machine" in [SECURITY.md](../SECURITY.md). |
| `key.<host>` | The key for one host, written by `codezaiku model key <host> …` (`key.openrouter.ai`, `key.api.anthropic.com`): sent to that host and to no other. A host with no key of its own gets `CODEZAIKU_API_KEY`. |
| `CODEZAIKU_AVOID_PLACES` | The places whose providers never serve a model reached through OpenRouter, as country codes with commas between them: `codezaiku config set avoid_places "CN, HK, MO"`. Unset: `CN, HK, MO, CU, IR, KP, RU, VE` (China with Hong Kong and Macau, Cuba, Iran, North Korea, Russia, Venezuela), the countries of concern of the United States Department of Justice's data security rule (28 CFR 202.601, in effect since 2025-04-08). `none`: no place. Every request to OpenRouter leaves out the providers that OpenRouter's own list says are based or run there; when none is left for a model, nothing is sent and the job says so. See [SECURITY.md](../SECURITY.md). |
| `CODEZAIKU_AVOID_BY_OWNER` | Whether a provider owned by a company based in an avoided place is left out too, whatever place it states for itself (a Singapore-registered arm of a company based in mainland China, for one). The owners come from a dated table that ships with the program, each row with its public source. `on` unless set; `off` lets those providers serve. |
| `CODEZAIKU_OPENROUTER_IGNORE` | More OpenRouter providers to leave out of every request, by OpenRouter's own names for them, with commas or spaces between: `codezaiku config set openrouter_ignore "fireworks, together"`. Unset: none besides the places'. |
| `CODEZAIKU_CTX` | Force the context window when a server does not report one (`/v1/models` `context_length` is read automatically). On the Claude API the default is the model's own window, and at most 200,000 tokens. |

A hosted drive is just `CODEZAIKU_DRIVE=https://api.anthropic.com` (no path) plus
`CODEZAIKU_MODEL` and `CODEZAIKU_API_KEY`. Per-response token usage is logged (`usage ←` lines;
`/cost` in chat totals them), and a failing endpoint stops the run after 8 consecutive errors. Since 0.3.13 a hosted model is
better kept as a saved model (`codezaiku model add …`, the rows `model.<name>.*` above) with its key kept for its host
(`key.<host>`), and chosen per job with `--model <name>`; `codezaiku spend` says what the jobs cost. See
[MODELS.md](MODELS.md), "Saved models, chosen per job".

**OpenAI's own API.** `CODEZAIKU_DRIVE=https://api.openai.com` (or the host word `openai` in `codezaiku model add`) is spoken to
through the Responses API, since its current models refuse tools on the chat-completions endpoint. `CODEZAIKU_REASONING_EFFORT`
(or the saved model's `effort`) is the depth of reasoning (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`; each model takes
some of these); the context window is `CODEZAIKU_CTX` or 272,000 tokens, the standard-price tier.

**The Claude API.** `CODEZAIKU_DRIVE=https://api.anthropic.com` is spoken to in Claude's own format (the Messages API), with
the key in `CODEZAIKU_API_KEY` (`codezaiku config set api.key <your key>`) and a model such as `claude-opus-5-5` in
`CODEZAIKU_MODEL`:

- `CODEZAIKU_REASONING_EFFORT` sets how hard Claude works on each reply (`low`, `medium`, `high`, `xhigh`, `max`). Unset, the
  model's own default is used.
- The tools, the system prompt and the conversation so far are cached at the API. A request that repeats them reads them from
  the cache at a fraction of the price (a twentieth on Claude Opus 5.5). The `usage ←` line of each reply says how many tokens
  of the prompt were read from the cache and how many were written to it. The API reads from its cache only up to the first
  thing that changed, so on this drive the rules of the work are the system prompt, and what CodeZaiku rebuilds every turn (the
  project's files, the goal, the files still owed, the check) is told after the conversation.
- The model's window, the longest reply it writes and the effort levels it knows are read from the API's own model listing.
  The current models read a million tokens; CodeZaiku fills at most 200,000 of them unless `CODEZAIKU_CTX` says otherwise,
  because a very long conversation costs several dollars a turn whenever the cache misses.
- Claude's thinking is not sent back to it on later turns. The log holds a summary of it for each reply.
- `CODEZAIKU_CLAUDE_DUMP=<folder>` writes every request as it is sent, one file each.

`codezaiku doctor` asks the model for a short reply and says in the API's own words when the key or the model's name is refused.
A gateway in front of the Claude API is named by the address of its Messages endpoint, `https://<gateway>/v1/messages`.

---

## Experiment flags

These exist to *measure* the system, not to run it, and several intentionally make it worse. They are
documented so the benchmark scripts in `bench/` are readable, not because you should set them:
`CODEZAIKU_LIB_OFF`, `CODEZAIKU_DEV_ANSWERS`, `CODEZAIKU_DEV_BASELINE`, `CODEZAIKU_EDIT_TELEMETRY`,
`CODEZAIKU_OPS_TELEMETRY`, `CODEZAIKU_OPS_TRIAL`, `CODEZAIKU_OPS_RECON_JAIL`,
`CODEZAIKU_OPS_PYTHON_SESSION`, `CODEZAIKU_FORCE_GROUNDING`, `CODEZAIKU_COMPACT_MASK_FIRST`.

`CODEZAIKU_COMPACT_MASK_FIRST` is off for a measured reason rather than a cautious one: it reclaims
context by masking old tool results before paying for a summary, and on read-heavy coding runs
compaction **never fired at all** — context peaked at 26% of a 32k window against a 70% trigger. Tool
output is already bounded when it is captured, so there is nothing for it to do. It stays in because
a long maintenance run might yet cross the threshold, and then it would.

A few internal knobs are deliberately absent from this page because setting them by hand is a
mistake rather than a choice — toolchain discovery (`CODEZAIKU_FIXTURE_JAVA_HOME`,
`CODEZAIKU_MISE_SHIMS`) resolves itself and `codezaiku doctor` reports what it found.

---

## Precedence

An explicitly set variable always wins over auto-discovery. Where CodeZaiku can work something out from
the environment — the app health endpoint, the verify command, the compose project — it does, and says
so in its output. That is why `fix <scope> <ceiling>` needs no per-stack configuration at all.

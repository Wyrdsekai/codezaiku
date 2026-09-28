# Changelog

## 0.3.11

This release adds Amazon Bedrock as a model, with your own AWS account. `codezaiku update now` updates ResearchZosho too, through ResearchZosho's own updater, and programs that keep CodeZaiku up to date get a JSON answer and exit codes to act on. `model serve install` uses a model server that another program already runs, such as Wyrdsekai's.

### Added

- Amazon Bedrock as the model, with your own AWS account. `codezaiku bedrock models` lists what Bedrock offers your account in a region, `codezaiku bedrock test <model>` sends one small request with a tool in it to see that you may use the model, and `codezaiku bedrock use <model>` makes it CodeZaiku's model. Claude, Llama, Nova, Qwen, Mistral and the other chat models go through Bedrock's Converse API, with tools and pictures.
- It uses your own AWS sign-in through the AWS command line (single sign-on, an assumed role, keys), signs each request itself, and saves nothing of that sign-in. What you may use is decided in your AWS account. When AWS refuses, the message says what AWS answered and which of the usual causes it is. `docs/BEDROCK.md` has the steps, and the policy for whoever manages the AWS account. AWS GovCloud and the China regions work by naming the region.
- `codezaiku doctor` checks a Bedrock drive by asking the chosen model for one token.
- `codezaiku update --json` and `codezaiku update now --json`, for a program that keeps CodeZaiku up to date, such as Wyrdsekai. Each prints one JSON document on stdout, with the same fields as ResearchZosho's updater, and progress goes to stderr. The exit code says what happened: 0 updated or already current, 75 another update is running (ask again later), 3 this install cannot update itself, 1 failed. With `--json`, only CodeZaiku is updated. `docs/DEPLOYING_AS_A_BACKEND.md` has the fields.
- One update at a time. An update holds a lock file in `~/.codezaiku` from start to end, and checks the version again against the installed files once it has the lock, because another program may have updated them in the meantime. A second update started meanwhile answers "busy" and changes nothing.
- `codezaiku model serve install`, and `doctor` when it offers to set up a model, first look for a model server that another program already runs on this machine: Wyrdsekai's on port 8200, llama.cpp, vLLM, Ollama or LM Studio. When it serves one of the models CodeZaiku knows, Qwen3.6-35B-A3B among them, which Wyrdsekai runs as its brain, CodeZaiku uses that server and downloads nothing. `model serve install --own` installs CodeZaiku's own model instead.

### Changed

- `codezaiku update now` keeps ResearchZosho up to date too. It updates CodeZaiku, then, when ResearchZosho is installed, asks ResearchZosho's own updater to update it and says what came of each. A failure of one does not stop the other. When another program is updating ResearchZosho at that moment, it says so and leaves it alone.
- Each program's own updater replaces its files. Once ResearchZosho is installed, CodeZaiku never downloads or replaces it: `codezaiku install researchzosho` asks its updater instead, which restarts its own service, so the advice to reinstall the service by hand is gone. A ResearchZosho older than 0.5.0 is asked the same way; its updater answers in words, and "already" there means up to date.
- `codezaiku update` shows ResearchZosho's installed and latest versions and its update setting below CodeZaiku's.
- Auto mode (`CODEZAIKU_UPDATE=auto`) updates CodeZaiku only. ResearchZosho's own service updates ResearchZosho, with its own setting. `doctor` and the chat name `codezaiku update now` when a newer ResearchZosho is out.
- The README's opening, the npm package's description and keywords, and the MCP Registry entry say what CodeZaiku does and which model servers it works with.
- The source uses imports instead of fully-qualified class names, 616 of them in 68 files. The program works the same.

### Fixed

- `codezaiku install researchzosho` updated an installed ResearchZosho by unpacking the plain tarball over it. On a machine without Java 21, a ResearchZosho that carried its own Java then no longer started. Its own updater now does the update and keeps that kind of build. A first install beside a CodeZaiku that runs on its own Java now takes ResearchZosho's build with its own Java too.
- On Windows, `codezaiku update now` downloaded the release and then failed, because the running program holds its own files, and its advice to try again from a new terminal could not work. It now says at once to close CodeZaiku and run the installer again, and exits with 3.
- A coding run whose model server went away for a moment, while it restarted or loaded its model, counted each refused request as a failed call, answered it with a note about malformed JSON, and ended after eight of them, within seconds. It now waits for the server, up to 10 minutes, and goes on with the same turn.

## 0.3.10

This release is about `codezaiku chat`. It shows you a plan before it builds, lets you steer a running turn, and no longer loses your answer or stops on a full context window.

### Added

- `chat`: a plan you approve before a build starts. When your message asks for something to be built or changed, the model first writes a short plan: the files, the approach, what it would install, what it will leave out. You say `go`, type a change, or `skip`. `/plan auto|on|off` controls it; `auto` is the default and applies to build-sized asks only. A question or a small edit gets no plan step.
- `chat`: yolo still asks before installing a package, starting a server or downloading from the network when the approved plan did not mention it. One "always" covers that kind for the session.
- `chat`: ctrl-C now pauses rather than only stopping. After the stop, the chat asks what should change. Type it and the work continues with that change; press Enter to leave it stopped.
- `chat`: a progress line every ten steps on a long turn: steps so far, time, and the files changed.

### Fixed

- `chat`: when a reply is cut off at the output limit in the middle of a tool call, usually a whole file in one `write_file`, the model is told what happened and to write the file in pieces. It used to be told only "you must act by calling a tool", which invited the same oversized write again. One such call cost 3.5 minutes for nothing.
- `chat`: a reply that was cut off at the output limit is never acted on. Its tool call has truncated arguments. A cut-off `task_done` used to end the turn with "(done)" and the answer was lost.
- `chat`: the project description in the prompt takes a tenth of the context window, measured in the model's own tokens, instead of a fifth at a guessed rate. On a 32k window it took 10,000 tokens and the fixed part of every request was 71%. The previous reply carried into the next turn is sized from the window too.
- `chat`: the task message is never trimmed to make a request fit. It was treated as an old observation, and after one such trim the model no longer knew what it was building.
- `chat`: the eighth-of-the-window limit on tool output is per step. Parallel calls in one step share it.
- `chat`: no single tool result may take more than an eighth of the model's context window. The tools capped their output at sizes meant for a 64k window; on a 32k window three parallel shell results filled 60% of it in one step and the request could not be sent. When a request still does not fit, the newest tool results are now trimmed too, not only the older ones.
- `chat`: a long turn could stop with "context overflow". The loop counted tokens by dividing characters by 3, which undercounts code and shell output, and it did not count the tool descriptions at all. It now uses the token count the server reports after each reply. If the server still rejects a request as too big, the loop trims old tool output and retries once.
- `chat`: the next turn now sees the previous question and reply. Before, each turn started with only a short restatement (topic, decisions, files seen), so a follow-up like "do everything but 7" referred to a list the model had never seen, and it started over instead of building.
- `chat`: the reply you see is now the model's own answer. It used to show the short `task_done` note instead ("delivered the analysis in my reply"), and the real answer was lost.
- `chat`: every turn started with an extra model call that planned the work and was then thrown away, because the context the chat adds in front of your words made the request look like a large task. On a 27B that was 30 to 60 seconds of nothing before the first step. The planning call is now skipped in chat.
- `chat`: the status line is never written while the chat waits for your answer to an approval question. In the first test build it overwrote the question, so the screen showed "… thinking" while the chat was in fact waiting for you.
- `chat`: while a turn runs you now see a status line: "… thinking · 23 s" between steps, "… running shell · 8 s" during a step. Each step ends with a short result line like "→ 84 lines". The turn ends with "done · 6 steps · 3 min 40 s". Before, the screen showed nothing between steps.
- `chat`: a research run the library refused at intake was announced at every start until you ran `/research read` on it, although there was nothing to read. It is now announced once. `/research read all` marks every finished run read, to clear a backlog of notices.
- `chat` started in a home directory could take minutes to show its prompt. At startup it walked the whole tree under the current directory to guess the project language, including hidden folders such as a 210 GB `.cache`. Every project walk now skips hidden folders and `snap`, stops after 50,000 entries, and the language guess stops after 5,000 files. The chat now starts in under a second there.

## 0.3.9

This release is about `codezaiku doctor`. Its advice is now easier to follow, and it no longer reports the wrong context window size when the model runs behind llama-swap.

### Fixed

- `doctor`: every "fix:" line now says what to do in plain words, with the command to run. The vulnerability scanner line gives the trivy install command for this operating system. The web search check passes when a Brave Search key is set, and no longer prints a `docker run` for a folder the download does not contain. The missing-model-server line points at `codezaiku setup` instead of a `docker run` with placeholders.
- `doctor`: the two context window checks now say in plain words what the numbers mean and what to do. The second one used to read "a host's task preamble sits on top of the pinned project block".
- `doctor` reported the model's context window as 8192 behind llama-swap, with a warning that no server reported one. It asked without the model's name, and llama-swap only answers for a named model. Runs were not affected; they always passed the name and read the real window.

## 0.3.8

This release fixes `codezaiku setup` choosing a model that cannot chat.

### Fixed

- `setup`: when a model server has more than one model, setup asked "Which model?" with the first name in the server's list as the default. Servers list their models in alphabetical order, so on a server with an embedding model the default was `embed`. Pressing Enter saved it, the one-word test failed, setup said "Saved anyway", and every command after that failed. Setup now says what the choice is, shows the models numbered, marks the ones that look like embedding or ranking models, and offers the model already in your settings as the default, or else the first chat model.
- `setup`: a model that does not answer the one-word test is not saved while another model is left to try. Setup says why and asks again. When nothing answers, for example while a model is still loading, the first choice is saved and setup says it is unchecked.
- `setup`: a server that lists no models asks for the model's name. A server with one model asks nothing.
- `setup`: an address typed with `/v1` on the end, such as `https://api.openai.com/v1`, is probed correctly. Setup's own checks went to `/v1/v1/…`.
- ACP: the `model` option no longer offers the drive's embedding models.

## 0.3.7

This release fixes places where CodeZaiku could hang or act on half an answer, and brings the ACP server up to the current schema.

### Fixed

- The drive client shares one HTTP client instead of building one per instance. Each client owns a thread or two, so long sessions leaked threads.
- MCP client: a server that logs a lot to stderr no longer blocks. Its stderr was never read, so the pipe filled and the server stopped.
- MCP client: a server that goes silent now fails the call after 60 seconds. The timeout was only checked when a line arrived, so a silent server hung the chat forever. Progress notifications extend the wait. `CODEZAIKU_MCP_TIMEOUT_SECONDS` changes the limit.
- MCP client: it reads every page of `tools/list`, answers `ping` and `roots/list` from the server, returns `structuredContent` when a tool sends no text, and leaves out arguments the model set to null. When a server dies at start, the error includes what it wrote to stderr.
- Streaming: a stream that ends without a finish reason or `[DONE]` is not accepted as a message. A dropped connection used to hand back half a tool call as if it were whole. The turn now falls back to a plain request. A stream that sends nothing for a whole drive timeout is closed.
- `fix`: the 30-second settle re-check after a failed verify now also runs when rollback is off or the target was not localized. Before, that case recorded `verified=false` for a service that was still coming back up.
- `run` and ACP file lists: a file that was already modified and is edited again by the run is now reported. So is a file the run edited and then committed.
- Harness git calls: the 20-second timeout now works. Output was read before the timeout started, so a hung git never timed out. `GIT_DIR`, `GIT_WORK_TREE` and related variables inherited from the caller are removed, so the file list comes from the workspace and not from the caller's repository. Git is run with `GIT_OPTIONAL_LOCKS=0` and `LC_ALL=C`.
- The shell tool sets `GIT_EDITOR`, `EDITOR`, `VISUAL`, `GIT_SEQUENCE_EDITOR`, `GIT_ASKPASS` and `SSH_ASKPASS_REQUIRE`, and removes an inherited `GIT_DIR`. A `git commit` without `-m` fails at once instead of waiting for an editor until the timeout.
- Ops and container commands that take stdin: stdin is written while the output drains and the timeout runs. Before, a child that did not read its stdin blocked the write before the timeout had started. Container file operations now have a timeout of 120 seconds; they had none.
- `web_fetch`: one deadline covers the whole fetch, including the body. The request timeout stops at the response headers, so a server that trickled the body could hold a turn for as long as it liked.
- MCP server: a tool call with a missing required argument now comes back as a tool result marked `isError`, with the argument's name, so the calling model can read it and call again. It used to be a JSON-RPC error (-32602), which most hosts treat as a failed request and do not show to the model. An unknown tool is still a JSON-RPC error.
- The `.deb` now requires Java 21. It used to accept `default-jre-headless`, which is Java 17 on Debian 12, so the package installed there and then failed to start. On a system without Java 21, `apt` now refuses the install. Use the install one-liner there; it takes the build that carries its own Java runtime.
- The config file holds API keys. It is now kept at mode 600, and `~/.codezaiku` at 700. With a umask of 002 both were group-readable. This is applied on every load and every write, so existing files are tightened the next time you run anything.
- A drive address ending in `/v1`, `/v1/chat/completions` or `/v1/models` now works. Requests used to go to `/v1/v1/…` and fail with 404.

### Added

- ACP: tool calls and permission requests carry the tool's `name` (optional in schema 1.22.0).
- ACP: `resource_link` blocks in a prompt are used. An attached file is named in the task, relative to the workspace, so the model can read it. They used to be dropped, so "fix the bug in @Main.java" arrived as "fix the bug in".
- ACP: a `usage_update` after each model call, with the tokens in context and the size of the window.
- ACP: the stdio MCP servers a client passes in `session/new` are started, and their tools join the session as `mcp_<server>_<tool>`. Before, `session/new` failed when the list was not empty, although the protocol says every agent must take stdio servers. Each call to a remote tool asks the client for permission. A server that does not start fails `session/new` with what it wrote to stderr. At most 40 remote tools are used (`CODEZAIKU_ACP_MCP_MAX_TOOLS`), and the first prompt says when some were left out. `http` and `sse` servers are still refused.
- ACP: `session/new` returns a `model` config option with the models the drive lists, and `session/set_config_option` switches the session to one of them. A host can show a model picker. harbor's `--model` flag needs it: without it harbor stopped with "ACP agent did not advertise a model-selection mechanism". Two harbor tasks now pass through `codezaiku acp` with a local 27B, one of them with a stdio MCP server passed by harbor.
- What a remote MCP tool returns is labelled as another program's output and put between markers, in the chat as well as in ACP. The output cannot close its own markers.
- `scripts/check-mcp-real-client.sh` connects the official MCP Python SDK client to `codezaiku mcp`. SDK 2.2.0 opens with `server/discover`, gets "method not found", falls back to `initialize`, and works. A test pins the error code that fallback depends on.

## 0.3.6

Fixed
- The usage text now lists `doctor` and `update now|status`. Both existed, and the release notes told you to run `update now`, but `codezaiku` with no arguments never mentioned either.
- Behind llama-swap, which `model serve install` sets up, the model server's context window was never found: the proxy answers `/props` with "no model id" because that request names no model, so the harness assumed 8192 and compacted at a quarter of the real window. The window is now read from the proxy's per-model path, `/upstream/<model>/props`.

Notable changes. This project follows [semantic versioning](https://semver.org/) loosely: while at
0.x, minor versions may change behaviour.

## 0.3.5

Fixed
- Setup's "say hello" check works with reasoning models. It used to give the model 64 tokens to reply. A model that thinks before it answers could use them all up on thinking and send back nothing. Now the check allows 400 tokens and a reply that thought but ran out of room counts as a hello.

Changed
- MODELS.md now starts with a table of which model to run for how much VRAM you have, the same table `codezaiku model serve install` picks from. The old opening paragraph said CodeZaiku does not manage a model server for you, which stopped being true in 0.3.3. The same table is on codezaiku.org/models.
- The usage text now lists `model serve install|status|stop|uninstall|check`. It had been missing.

## 0.3.4

Fixed
- The MCP server introduced itself as version 0.1 whatever the release; it now says the release's version. (Its sibling ResearchZosho 0.1.8 fixes the same line, and its drive probe, which named no model and so read every llama-swap drive as absent; CodeZaiku's probe already named the model.)
- `codezaiku model serve check` (which the release build runs) read a rate-limited host (HTTP 429) as a missing file; it now asks again, twice, twenty seconds apart, and then reports the row as not checked rather than gone.

## 0.3.3

Added
- `codezaiku setup`: the first ten minutes as questions with defaults. The model (a server it finds, one this machine serves on demand, or a hosted API with a key, then one word asked of it), a web search backend (a Brave key or a SearXNG address, checked), Claude Code, Codex and Gemini CLI connected over MCP when installed, and ResearchZosho offered. The installers name it as the next step.
- `codezaiku model serve install`: the model on this machine, on demand, on Linux, macOS and Windows. It picks the measured model for the machine's memory, downloads it once, puts llama.cpp behind a small proxy (llama-swap) as a service that starts with your session on port 8211, and sets the drive to it: the model comes up when asked and goes away after 20 idle minutes, so the memory is free in between and nothing has to be up all the time. On Linux the server is llama.cpp's CUDA container (Docker with the NVIDIA runtime); on macOS its Metal build, sized by unified memory, as a launchd agent; on Windows its Vulkan build, which runs on any card, as a logon task. Every download is checked against a recorded sha256 (the model files as Hugging Face lists them, the proxy's published checksums, the pinned llama.cpp build) and refused on a mismatch; `codezaiku model serve check` reads every row and pinned build, and the release build runs it. Uninstall refuses while the other product's settings still point at the proxy (`--force` overrides). `status`, `stop` and `uninstall` beside it; `--share` lets other machines use this card, and a proxy already on 8211, ResearchZosho's included, is used as it is. `codezaiku doctor` names it as the fix when no model server answers, and offers to run it when a person is at the keyboard.

## 0.3.2

Added
- `npx -y @wyrdsekai/codezaiku-mcp` starts the MCP server from any client that runs npm packages: the launcher finds an installed CodeZaiku, or fetches the release of the same version, checks it against the release's checksums and unpacks it under `~/.codezaiku/launcher`. CodeZaiku is listed in the MCP Registry as `io.github.Wyrdsekai/codezaiku`.
- Builds with their own Java runtime, one per platform (Linux and macOS on x64 and arm64, Windows x64): `codezaiku-<version>-<platform>.tar.gz`, nothing to install first. The install one-liners take one when the machine has no Java 21 (`CODEZAIKU_RUNTIME=1` asks for it), the npm launcher does the same, and `codezaiku update` on such an install stays on its own kind.

## 0.3.1

Fixed
- `codezaiku update now` failed with "HTTP 302" on a tarball install: a GitHub release asset is served through a redirect and the downloader did not follow it. The update check, the deb path, and `codezaiku install researchzosho` were unaffected. On 0.3.0, update with the install one-liner instead.

## 0.3.0

Added
- Chat: `/research <topic>` builds a research brief in conversation; `/research go` files the run; `/research status` and `/research read` follow it. A finished run is announced once in the next chat.
- Research memory: `codezaiku research` keeps its findings with sources in `~/.codezaiku/research`; later runs on related questions start from it. Works with nothing else installed.
- `codezaiku install researchzosho`: downloads the ResearchZosho release, verifies it, runs its setup, connects the chat (write token, research memory questions handed over). Updates an existing install in place. `/setup librarian` in chat does the same.
- `codezaiku doctor` and the chat banner report when a newer ResearchZosho is released.
- `codezaiku update [now | auto on|off]`: install the latest CodeZaiku release in place (checksum-verified; tarball installs). `CODEZAIKU_UPDATE=auto` lets a chat do it at its start. `doctor` reports a newer CodeZaiku.
- `web_search` fallback: Wikipedia plus Crossref and OpenAlex when neither Brave nor SearXNG answers. `CODEZAIKU_FALLBACK_SEARCH=off` disables it.
- `scholar_search` tool (Crossref and OpenAlex, results by DOI) in every research run.
- `web_fetch` refuses loopback, link-local, unspecified and multicast addresses and the service's own hosts (`CODEZAIKU_FETCH_PRIVATE`, `CODEZAIKU_FETCH_MAX_BYTES`).

Changed
- ResearchZosho is a separate program (https://researchzosho.org). The copy that shipped inside CodeZaiku is removed. `codezaiku librarian …` runs the installed `researchzosho`. CodeZaiku talks to it over HTTP with the published client.
- The `library_*` tools and library resources are removed from CodeZaiku's MCP server. ResearchZosho has its own MCP server.
- CI and release workflows use GitHub-owned actions only.

## 0.2.0 — the conversation release

The chat surface, orchestration, and a research capability that compounds.

- **`codezaiku chat`** — a conversation in one project: it asks before writing or running
  anything (showing the change first), remembers what you allow (`/trust`), steps back through
  its own actions (`/undo`, per-step journal), and keeps sessions you can `/resume`, `/onboard`
  from, or hand off. Streaming replies; thinking hidden unless you ask (`/thinking`).
- **Project memory** — `/remember` carries facts into every future session here; the model can
  save too, and asks first. Working agreements: the project's `FAMILIAR.md`/`CLAUDE.md` rides
  every turn. Conflicts with recorded decisions are said out loud, never silently overridden.
- **Background work and delegation** — `run_background` keeps long commands out of the
  conversation's way (`/tasks`, completion notices); `delegate` hands a self-contained task —
  coding or research — to a background sub-agent, optionally on a different model
  (`CODEZAIKU_DELEGATE_DRIVE`).
- **Research that fans out** — `codezaiku research <q> fan` decomposes a question, researches
  sub-questions in parallel with fresh contexts, lets a critic decide whether coverage is
  sufficient, then synthesizes. Measured on WideSearch: 0.171 → 0.314 (local model), 0.728
  (hosted frontier model), same harness.
- **Web search backends** — Brave Search API first when `CODEZAIKU_BRAVE_KEY` is set, SearXNG
  (`CODEZAIKU_SEARXNG`) as fallback; web tools join chat when either is configured.
- **OpenAI-compatible façade** — `codezaiku v1` serves the chat as `/v1/chat/completions`
  (read-only tools by design: that wire cannot ask permission). Point Open WebUI at it.
- **MCP client** — `CODEZAIKU_MCP_SERVERS` makes other processes' MCP tools callable from chat,
  consent-gated per call.
- **Sessions as archives** — `codezaiku sessions export|import` (backup/restore; import never
  overwrites without `--force`).
- **Hosted drives** — `CODEZAIKU_DRIVE=https://api.anthropic.com` (+ `CODEZAIKU_MODEL`,
  `CODEZAIKU_API_KEY`, `CODEZAIKU_TEMP=none`) runs any surface on a hosted model; per-response
  token usage is logged; a failing endpoint stops the run after 8 consecutive errors instead of
  retrying forever.
- **Attestation predicate moved to `codezaiku.org`** (a domain we own). 0.1.0 and 0.1.1 are
  immutable and verify only with the old `codezaiku.dev` type — see SECURITY.md.
- Platform validation: the chat conversation battery passes 11/11 on Linux and macOS against a
  live model; Windows native validated end-to-end (7/7).
- Known gap: on macOS, ctrl-C turn-cancellation is unverified when stdin is a pipe (the signal
  is consumed without stopping the turn); interactive terminal use is the supported path there.

## 0.1.1 — fixes

- **`run --text` accepts `@<file>` and `-`.** On Windows every argument crosses cmd.exe, which
  refuses a command line over 8,191 characters, so a dispatch carrying a real task never started.
  The file and stdin forms work on every platform, and match the `@file` form `code`, `fix` and
  `research` already take. An unreadable `@file` is an error, not a literal task.
- **`doctor` reports a second context-window floor.** 8k passes the required check and still cannot
  complete a run driven by another agent; 12k is the floor for `run`, MCP and ACP.
- **`install.ps1` prints one line when a prerequisite is missing**, not a PowerShell error record.
- **`bin/codezaiku` runs in the directory you called it from.** From a source checkout, `run` used
  the checkout as the workspace instead of your project. The packaged launcher was never affected.
- **The tarball contains `LICENSE` and `README.md` again.** The 0.1.0 tarball shipped without them.
- **`docker build -f packaging/docker/Dockerfile .` works from a clone.** It referenced a path that
  only exists in the private tree, so it failed for everyone else.

## 0.1.0 — first public release

The initial open-source release. CodeZaiku has been developed and measured privately; this is the
point at which it became installable by someone who is not its author.

### Platforms

Linux is the reference platform. macOS (Apple Silicon), **native Windows** and WSL2 are all measured
— Windows requires Git for Windows, whose bash the harness shells out to. `scripts/verify-platform.sh`
runs the acceptance checks on any host, and `PLATFORMS.md` carries the per-platform numbers.

### Release artifacts

A platform-independent tarball, a Debian package and a container image, each listed in `SHA256SUMS`
and signed after publication with a per-asset Sigstore bundle. Verify with
`gh attestation verify <asset> --repo Wyrdsekai/codezaiku --predicate-type https://codezaiku.dev/attestation/release/v1` (needs GitHub CLI 2.49+). The signature is an authenticity statement
about bytes built and validated on real hardware — not build provenance from a CI runner. See
[SECURITY.md](SECURITY.md).

### Surfaces

- **Coding** — `code`, `loop`, `decompose`, `review`. A single growing-conversation loop with no
  planner and no in-loop gates; the model decides it is done and the harness verifies afterwards.
- **Operations** — `fix`, `watch`, `serve`, `triage`, `investigate`. Senses a stack, localizes the
  failing service, applies a fix, verifies it against the stack's own health endpoint, and rolls back
  if verification fails.
- **Security** — `secure`. Deterministic posture checks plus runtime intrusion detections, and a
  bounded read-only investigation that proposes one containment command. Report-only at every
  authority rung.
- **Research** — `research`. Search, fetch and synthesize against live sources, with an accumulating
  findings pool.
- **Integration** — three ways for another program to drive CodeZaiku, all answering with the same
  result document: `run` (one task, one JSON document on stdout, the subprocess CWD is the
  workspace; exit code agrees with `status`, and `--mode artifact` says the deliverable is a single
  file so the run does not build tests around it), `acp` (Agent Client Protocol v1 over stdio — streams tool activity, cancellable
  mid-turn, routes git writes to the client's consent flow), and `mcp` (stdio JSON-RPC exposing every
  surface as tools). `serve` exposes HTTP.

### Safety

- Authority ladder: `observe` < `localize` < `propose` < `guarded` < `unattended`, defaulting to
  `propose`.
- Blast-radius limiting, host-write guard, snapshot-and-rollback, harm check, kill switch, JSON audit
  trail.
- Indirect prompt injection through container logs is mitigated structurally: the model may only choose
  among services a deterministic sensor already flagged, and declines to localize when nothing is
  flagged.

### Install

- `scripts/install.sh` for a user, system or custom prefix, with `--uninstall` and `--purge`.
- Debian package via `packaging/deb/build-deb.sh`. The systemd unit ships disabled.
- `codezaiku doctor` reports what is missing and the command that fixes it.
- `codezaiku init` and `codezaiku config` manage settings in `~/.codezaiku/config`; environment
  variables override the file.
- `codezaiku model detect|add|use` finds and switches model endpoints. CodeZaiku runs no inference
  server and bundles no weights.

### Known limitations

See [LIMITATIONS.md](docs/LIMITATIONS.md) — it is the honest accounting and is worth reading before
deciding whether this is useful to you. In short: coding is capability-bound on small models, incident
diagnosis on public benchmarks is weak, and there is no long-running soak evidence yet.

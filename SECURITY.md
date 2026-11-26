# Security

Two things belong here: how to report a vulnerability, and what CodeZaiku's own threat model is. The
second matters more than usual, because this is software that reads untrusted input and can change your
systems.

## Reporting a vulnerability

Please report privately first, by either route:

- a [private security advisory](https://github.com/Wyrdsekai/codezaiku/security/advisories/new) on
  this repository, or
- **security@codezaiku.org**

Do not open a public issue for an unpatched vulnerability.

Useful reports include what an attacker controls, what they achieve, and ideally the reproduction. We
are particularly interested in anything that gets attacker-chosen text into a model prompt in a way that
changes what the agent does.

## What CodeZaiku can do to your systems

Be clear-eyed about this before running it with authority:

- It executes shell commands on the target — locally, over ssh, or in a container.
- At `guarded` or `unattended` it modifies live services: restarting them, changing configuration,
  running engine CLI commands.
- It reads logs, environment variables and configuration, which routinely contain secrets.

Defaults are conservative: the ceiling is `propose` (it tells you the command rather than running it),
and security findings are report-only at **every** rung.

## The guard stack

| Guard | What it prevents |
|---|---|
| **Authority ladder** | An unproven fix auto-applying. `observe` < `localize` < `propose` < `guarded` < `unattended`; the effective rung is the minimum of your ceiling and what the situation earned. |
| **Blast radius 1** | A fix touching any service other than the one localized. Verified to block a bystander restart. |
| **Host-write guard** | The agent editing host files or the compose file itself. Fixes must act inside the target. |
| **Rollback** | A destructive fix leaving a service broken. Snapshot → apply → verify → restore on failure. |
| **Harm check** | A "successful" fix that broke a healthy dependency being reported as success. |
| **Read-only diagnosis** | Investigation changing what it is measuring, including mutating commands hidden behind `docker exec`. |
| **Kill switch** | Everything. `CODEZAIKU_OPS_HALT=on` forces `observe`. |
| **Audit trail** | Not knowing what happened. Set `CODEZAIKU_OPS_AUDIT`. |

## Indirect prompt injection — the one to understand

**The operator reads container logs and puts them in front of a model. Logs are attacker-writable.**
Anything that logs a request path, user-agent, or echoed error body lets an outsider write text into the
agent's reasoning context.

We measured this against our own shipping prompt. A single injected log line steered the agent to an
attacker-chosen service **40 times out of 40 — every payload, every run — on both a 9B and a 30B.**
Model scale was not a defence.

The mitigation is structural, not instructional, because instructing a model to ignore hostile text does
not work reliably:

1. The model may only choose **among services a deterministic sensor already flagged**. It can
   disambiguate; it cannot nominate. An attacker who cannot make a service genuinely look unhealthy
   cannot make it a target.
2. An answer outside that candidate set is rejected as suspected injection.
3. If no sensor flagged anything, the agent **declines to localize** rather than reasoning from log text.
4. Untrusted text is fenced and labelled, and instruction-shaped strings in it are neutralized.

Result: product-effective steering **0/80** on both tiers, with no loss of accuracy.

**The bounded claim:** eight payloads, one attack channel, no adaptive attacker. This is "we measured one
channel and closed it", not "injection-proof". The same neutralization is applied to security alert
fields, since file paths and process names are attacker-chosen too — a container that names a file
`IGNORE PREVIOUS INSTRUCTIONS…` gets that string into the alert stream, and we verified it arrives
defanged.

## What leaves your machine

Your project is a folder on your disk, and what CodeZaiku writes (settings, chats, the research memory, the record of a
job's commands) stays in `~/.codezaiku`. Nothing is sent anywhere to be stored. This section says what CodeZaiku and the
programs it starts send out, and to whom; the sections after it say more about each part.

Where this section says "measured", it was measured on 2026-11-26 on Linux with version 0.3.20 before its release, with no
ResearchZosho, no SearXNG and no search key in use, but for stand-ins where a line says so. Every connection that
CodeZaiku and every program it started made, and every name they looked up, was recorded (with `strace`) and checked against
CodeZaiku's own record of what its proxy let through and refused. A run in which a command connected to an outside address on
purpose showed that connection; a run whose commands connected nowhere showed none. The runs are listed at the end of this
section.

### What goes to the model

A job sends its model your words, the files it reads and what its commands print. That goes to the model server's address.

- **A model on your own machine:** it stays on the machine. "A model on your own machine" below says what the model server
  itself can reach.
- **A model server on your network:** it goes to that machine. An `http://` address is not encrypted, so anyone who can watch
  that network can read it. In a coding job on such a model, the model server was the only machine CodeZaiku itself connected
  to, apart from the package registries it reached for the job's `pip` when the commands' network was closed.
- **A hosted model:** it goes to the host, and through OpenRouter to the provider OpenRouter picks for each request. In a coding
  job on DeepSeek V4.1 Flash through OpenRouter, CodeZaiku connected to `openrouter.ai` for the model, and to the package
  registries for the job's `pip` (below): 14 requests to the model, served by three providers, which the job's record names.
  CodeZaiku also reads OpenRouter's public lists of models, endpoints and providers, without a key. A key is sent only to the
  host it is kept for. See "A hosted model".

### What a job's commands reach

On a hosted model a job's commands have no network of their own; on any other model they have the network unless you close it
("A job's commands and the network" says how). Measured with one job: make a virtual environment, `pip install pytest`, fix a
test, commit, and fetch `https://www.python.org/` with `curl`.

| the job's model | the commands' network | what the commands reached |
|---|---|---|
| hosted, through OpenRouter | closed, the default for a hosted model | CodeZaiku only. CodeZaiku fetched `pypi.org` and `files.pythonhosted.org` for `pip`, and refused `www.python.org` without looking it up |
| a model server on the same network | open, the default for it | `pypi.org`, `files.pythonhosted.org` and `www.python.org`, by themselves |
| the same | closed (`sandbox.network closed`) | as on the hosted model |

In both closed runs the commands looked up no name and connected to nothing but CodeZaiku, and CodeZaiku's record of the hosts
it let through and refused matched the connections one for one.

### What CodeZaiku asks by itself

| what | where | when |
|---|---|---|
| the latest release | `api.github.com` | below |
| a search with no words, and its settings (`/config`); no key goes with either | the SearXNG that `searxng` names | `codezaiku doctor`, when no Google or Brave key is set; `codezaiku setup`, when you give it a SearXNG address |
| one search for the word `test`, with the key | Brave | `codezaiku setup`, when you give it a Brave key: it says so before it sends it, and Brave counts it |
| OpenRouter's lists of models, endpoints and providers | `openrouter.ai` | when a job or a chat on a model through OpenRouter starts (the providers at most once a day), and at `codezaiku model add`; no key is sent |
| a Gradle distribution, a Python for uv | `services.gradle.org`; uv's own download | before a job's first command, when the project needs one ("A job's commands and the network") |

A SearXNG answers a search with no words without asking any of its search engines.

`CODEZAIKU_UPDATE` decides the release check:

- `check`, the default: `codezaiku doctor`, and a chat when it starts (in the terminal, and in the web app when a project's chat
  page opens), ask GitHub for CodeZaiku's latest release, at most once a day. Where ResearchZosho is installed (its command on
  your `PATH`, or where its installer puts it) they ask for ResearchZosho's latest release too; where it is not, nothing is
  asked about it.
- `auto`: the same requests, and a newer CodeZaiku is downloaded from GitHub and installed for the next start. The web app
  also asks once a day, at a quiet moment.
- `off`: CodeZaiku asks GitHub nothing by itself.

`codezaiku update` asks every time you run it, whatever the setting, and `codezaiku update now` downloads the newer release.
Each request carries the name `CodeZaiku/<version>`, and GitHub sees your machine's address.

Measured, requests to `api.github.com` in one run of each (ResearchZosho's command was a stand-in on the `PATH`):

| | `check` | `off` |
|---|---|---|
| a chat's start | 1; 2 with ResearchZosho | none, with or without ResearchZosho |
| `codezaiku doctor` | 1; 2 with ResearchZosho | none, with or without ResearchZosho |
| the web app, when a project's chat page opens | 1 | none |
| `codezaiku update` | 1 | 1 |

Also measured: `codezaiku --version` contacted nothing, and neither did `codezaiku web` while it started and served its first
page. A coding job (`codezaiku run`) contacted its model and, for its commands, what "What a job's commands reach" says, and
nothing else.

### Searches and pages

Where a search goes is said under "The places CodeZaiku avoids": a search service you set a key for, else the SearXNG that
`searxng` names, else Wikipedia, Crossref and OpenAlex. Each gets the words of the search. A page is read from its own site,
which learns your machine's address and the time; which pages may be opened is in "What a model can make CodeZaiku ask".

Measured in a chat with one search and one page read: the search went to `en.wikipedia.org`, `api.crossref.org` and
`api.openalex.org`, the page to `docs.kernel.org`, the address the message named. With the release check off, the chat
contacted nothing else but the model server.

With ResearchZosho installed, the chat asks the library before each turn what it holds on your message: at
`http://127.0.0.1:4649`, on this machine, unless `CODEZAIKU_LIBRARIAN_URL` names another address. What the library sends out is
on ResearchZosho's own security page.

### Names looked up

Each host in this section is looked up through your machine's name service, which asks the name servers of your network, or the
ones it is set to use. They learn the names, not what is sent. A command on a closed network looks up nothing, and CodeZaiku
does not look up a host it refuses.

### What you turn on yourself

- **A search key** (Serper, SerpApi, Brave): that service gets the words of each search, and the key.
- **A Firecrawl key:** a page CodeZaiku could not read itself is read by Firecrawl, which gets the page's address.
- **An MCP server:** it gets what is sent to it ("Other MCP servers").
- **Push, fetch and pull requests:** your git host, with your own credentials ("Push, fetch and pull requests").
- **A host let in** (`sandbox.hosts`, or a yes in the chat): a command can send it anything.
- **A fallback model** (`fallback`): a job whose model does not answer runs on the saved model it names, and sends it what it
  would have sent the first.
- **An alert webhook** (`CODEZAIKU_OPS_ALERT_WEBHOOK`): the ops commands post their alerts to it.
- **`codezaiku model serve install`** and **`codezaiku install researchzosho`:** they download from Hugging Face and GitHub, and
  refuse a file whose checksum is not the one recorded for it ("A model on your own machine"). `codezaiku model serve check`
  asks Hugging Face and GitHub about each recorded file without downloading it.

### What is never sent

CodeZaiku has no account and sends no usage counts and no crash reports. What it keeps in `~/.codezaiku` (chats, sessions, the
research memory, settings) goes nowhere but into what a job or a chat sends its model, and a key only to its own host. A job's
commands are given none of the keys and tokens in your environment.

### Not measured

- A search through a search key or through a SearXNG. The built-in fallback was measured.
- A machine with ResearchZosho installed, and the library's answer before each chat turn. Its release check was measured with
  a stand-in for its command.
- Sessions over MCP and ACP.
- macOS and Windows.
- `codezaiku model serve install` and `model serve check`, `codezaiku install researchzosho`, an update being installed, the
  Gradle and uv downloads, push, fetch and pull requests, and `setup` trying a Brave key.
- What the name servers, the registries, the model's host and its providers do with what they receive.

### The runs

All on 2026-11-26, Linux, version 0.3.20 before its release, each in a new settings folder.

| run | what was run | CodeZaiku connected to | its commands connected to |
|---|---|---|---|
| a coding job on a hosted model | `codezaiku run` on DeepSeek V4.1 Flash through OpenRouter, the commands' network closed by default: 13 turns, 14 requests, the job done | `openrouter.ai`; `pypi.org`, `files.pythonhosted.org` for `pip` | CodeZaiku only |
| the same job on a model server on the same network | `codezaiku run`, the commands' network open by default: the job done | the model server | `pypi.org`, `files.pythonhosted.org`, `www.python.org` |
| the same, the network closed | `codezaiku run` with `sandbox.network closed`: the job done | the model server; `pypi.org`, `files.pythonhosted.org` for `pip` | CodeZaiku only |
| a chat | `codezaiku chat` on the model server on the same network, the release check off: one search, one page read | the model server; `en.wikipedia.org`, `api.crossref.org`, `api.openalex.org`; `docs.kernel.org` | no command ran |
| a chat's start | `codezaiku chat`, the release check off, then the default | nothing; then `api.github.com` | |
| `doctor`, no model server answering | `codezaiku doctor`, the release check off, then the default | nothing; then `api.github.com` | |
| checking a SearXNG | `codezaiku doctor`, and `codezaiku setup` given a SearXNG address, against a stand-in that records what it is asked | the SearXNG: a search with no words and `/config`, with no key, from each | |
| saving a hosted model | `codezaiku model add` for a model through OpenRouter, no key | `openrouter.ai` | |
| the release check by hand | `codezaiku update`, the release check off, then the default | `api.github.com`, each time | |
| the version, the web app | `codezaiku --version`; `codezaiku web`, its start and first page | nothing | |

## What a model can make CodeZaiku ask

While CodeZaiku carries out something a model asked for, it opens a web address only when the address was given:

- **you wrote it**: in the task, in a chat message, or in the project's instruction file (`FAMILIAR.md`, `CLAUDE.md`,
  `AGENTS.md`) as it was when the conversation began. An address you wrote without `http://` counts too: a name
  (`example.org/page`), and a machine by its numbers, alone or with a port or a path (`192.168.1.1`, `192.168.1.20:8096/web`);
- **a search returned it**, or **a page read from a given address holds it**, in its text or as a link;
- **a file of the project holds it, as the file was before the job began**: a README's links, a manifest's URLs. A file
  the job wrote or changed gives nothing, whether a tool or a command changed it, and neither does anything in the
  project's `.codezaiku` folder, which holds what models wrote. In the chat the whole conversation is one job, and so is a
  job the chat's model delegates.

What a command printed never gives an address: the model wrote the command. Neither does a model's own text: an earlier
reply, a delegated job's answer, the research memory, a note carried over from another project.

The request is made in the spelling and with the scheme the address was given in, however the model wrote it: a slash
after it, `www.` before the host added or left off, a letter as an escape, `http` for `https`, a full stop after it all
become the given address again. The scheme's own port written out (`:443` after `https`) is the same address; any other
port is another program's, and is not.

You can name a thing without its address. CodeZaiku builds the address itself for a preprint by its arXiv number, a work
by its DOI, a repository by its owner and name on GitHub, a Wikipedia article by its title, and the front page or
`robots.txt` of a site you gave a page of. The name or the number has to stand in your words or in something a search or
a page returned.

The services CodeZaiku is written for are asked with the model's words as the words of a search, never as an address
they spell: the search services, Crossref and OpenAlex, Wikipedia's title search, and the data services the research
prompt names (the World Bank's indicators, the NVD, endoflife.date, OSV, CISA's list of exploited vulnerabilities), each
only in its own request shape.

Where a server sends a request on: a request for a given address holds nothing of the model's, so its server may send it
anywhere, and the address it leads to counts as given from then on. A request to a service may hold the model's words,
so it is followed only within the same site, to another such service, or to a given address.

A page's address handed to Firecrawl, which reads a page CodeZaiku could not, has to be a given address too.

Anything else is refused before it is sent. The model is told what it can open; the refusal goes into the run log, and in
the chat into the conversation's record, with the tool, the host and the rule.

What this leaves:

- The words of a search are the model's to choose, and they go to the search services.
- A model chooses which of the given addresses is opened. To somebody who could watch those sites, each choice among
  sixteen addresses says four bits.
- The site of a page that is opened learns your machine's address and the time, as with any browser.
- A command the model runs (`curl`, a package install, a test that calls a server) is not held to this rule. On a hosted
  model it reaches only what the next section lists; on a local model it reaches the network by itself unless
  `sandbox.network` is `closed`.

## A job's commands and the network

The commands a coding job runs (the shell, the project's tests, git) run in the sandbox. When the job is on a hosted
model they have no network of their own. On Linux each project's commands get a network that holds nothing but its own
loopback, and the one way out of it leads through CodeZaiku, which lets a connection through or refuses it by its host
and port and writes down every one. It does not read what a connection carries. macOS cannot give a command a network of
its own; there a command reaches this Mac's own addresses and nothing beyond them, and CodeZaiku listens on one of them
(see "On macOS" below).

A command reaches:

- the package registries: PyPI (`pypi.org`, `files.pythonhosted.org`), npm (`registry.npmjs.org`), Maven Central
  (`repo.maven.apache.org`), Gradle's plugin portal and distributions (`plugins.gradle.org`, `services.gradle.org`),
  crates.io (`index.crates.io`, `static.crates.io`) and Go's module proxy (`proxy.golang.org`, `sum.golang.org`);
- a host you wrote yourself: in the task, in a chat message, in `/research` or in a change to a plan;
- a host you let in.

Nothing else: not a host a search, a page or a file of the project named (a project's files are their author's words, and
a link to a site in a README would open all of that site to every command), not the project's own git remotes until you
name them, not git over ssh, no name lookups, nothing over UDP. A server a command starts on `127.0.0.1` is reachable from the job's next command.
On Linux this machine's own services are not; on macOS they are (below).

A project's Gradle wrapper downloads Gradle from GitHub, which a command does not reach. CodeZaiku fetches that
distribution itself before the job's first command, from `services.gradle.org`, checks it against its checksum, and puts it
where the wrapper looks for it. uv downloads a Python from GitHub too: when the project's `.python-version` or
`requires-python` (as they were when the job began) names a plain version the run does not have, CodeZaiku installs it before
the job's first command with your own uv, outside the sandbox, into the folder where uv in the sandbox looks. A job that runs
no command fetches nothing.

Every try is written to `~/.codezaiku/sandbox/network/<project>-<id>.jsonl`: when, the command, the host and the port, let
through or refused, and why. A refused host is named in what the command gets back, at the end of the job, and in
`networkRefused` in the result of `codezaiku run`. To let a host in:

```
codezaiku config set sandbox.hosts "github.com, api.github.com"
```

In the chat you are asked the first time a command is refused a host. A yes lets that host in until the chat ends.

A job on a local model keeps the network as before. `codezaiku config set sandbox.network closed` closes it for any model;
`open` opens it for any model.

What this leaves:

- A host that is let in can be sent anything. CodeZaiku sees the host, not what goes to it: with `github.com` let in, a
  command can push to any repository there with a token it holds.
- A registry learns what was asked of it: the names and versions of the packages a job installs, from your address.
- A registry that takes uploads can be sent one. npm's registry is let in, so a command holding a token for it could
  publish a package there. CodeZaiku passes a command no token of yours; such a token would have to stand in the
  project's files or in something the model read.
- Where the system allows no user namespaces (Ubuntu restricts them unless told otherwise), the network is narrowed, not
  closed: a command may still connect to one port, CodeZaiku's, of any address, and a Gradle build or a test that starts a
  server on `127.0.0.1` fails. The job's first line says so, and how to get the closed network on such a system where a
  documented way is known.
- On Windows a job on a hosted model runs no commands.

### On macOS

macOS's sandbox (`sandbox-exec`) gives a command no network of its own. With the network closed, a command reaches this
Mac's own addresses and nothing else. CodeZaiku listens on `127.0.0.1` for each project, on a port of the project's own
between 47000 and 47999 (the same every time, so a Gradle daemon left running finds it again; when another program has it,
the next free one, and the job says so at its first command), and the registries and the hosts
you named are reached through it as on Linux, with the same record and the same refusals. No other machine is reachable,
not even one on your own network; no name is looked up (`localhost` still is); nothing goes out over UDP or ICMP.
Java programs a command starts get `-Djava.net.preferIPv4Stack=true`: Java writes `127.0.0.1` in an IPv6 form on its
sockets, which macOS's sandbox does not count as this Mac's own, and without it Gradle and a Java test's own server fail.

Two things are not as on Linux, and macOS's sandbox rules cannot change them:

- **Every service of this Mac stays reachable from a command:** anything listening on `127.0.0.1` or on another of the
  Mac's own addresses, such as a local model server, a database, or a program that passes connections on to somewhere
  else. The sandbox's rules name this Mac's addresses only as a whole, and a port cannot be taken out of them.
- **A server a command starts can be reached from your network.** If a command was made to start a server on every
  address and wait (by text the model read, for example), somebody on a network that reaches your Mac could connect to it
  while it runs and be sent whatever the command holds. That person would have to be on the same Wi-Fi or office network,
  or on the internet where your Mac has a public IPv6 address and no firewall in front of it, and would have to know or
  find the port and connect while that server runs (a command can leave one running after it ends). To shut that door:

  ```
  codezaiku config set sandbox.listen closed
  ```

  A command may then not listen for connections at all, so a test that starts a server fails, and so does a Gradle build.

On Linux neither applies: a command's network there is the project's own, so this machine's services are out of its reach
and a server it starts can be reached only by the project's other commands.

## Push, fetch and pull requests

A job's commands have none of your credentials, and on a hosted model they cannot reach a git host. So CodeZaiku pushes,
fetches and works with pull requests itself, outside the sandbox, with your own git settings and credentials. The model
asks for it with a tool (`git_push`, `git_fetch`, `pull_requests`) that takes names: a remote, a branch, a pull request's
number. No command line of the model's runs outside the sandbox.

Who says yes:

- In the chat, every push and every pull request CodeZaiku would open is put to you first, in yolo mode too. The question
  shows the remote's address, the branch, the commits with their authors and files, and whether CodeZaiku has pushed this
  project there before. A force push happens only when your own words asked for one; the question says what it replaces.
- `codezaiku code`, `codezaiku run` and jobs started over MCP or ACP have no git tools unless `git.push` is set. With it they
  push and open pull requests only on the repositories it lists, and never force a push. Over ACP the editor or agent is
  also asked through its permission request each time; its yes comes on top of the setting, never in its place.
- Fetching and reading pull requests asks nobody: they read the project's own remotes.

The project's git folder is written by the job's commands, so CodeZaiku runs no git in it outside the sandbox:

- It pushes from a repository of its own under `~/.codezaiku/git/`. That repository takes the commits from the project by
  a fetch whose other end runs inside the sandbox, so it can only take what the job's commands could read.
- It pushes to the remote's address as the project's settings held it when the job began. A remote the job adds, changes
  or rewrites (`pushurl`, `insteadOf`) is not used. Nor is an address that is a folder on this machine, a `file://`
  address or one of the form `<transport>::<address>`.
- Nothing of the project's settings runs: no hook and no hooks path of the project's (`pre-push` and the rest), no
  `core.sshCommand`, `credential.helper`, `core.askPass`, `core.fsmonitor`, attributes or filters. Your own global and
  system settings, credentials and hooks are used: a `pre-push` hook you set up for every repository runs on a push made in
  your name, and when it refuses, you and the model are told what it said. On a Mac that includes the keychain's credential
  helper, which Apple's git names in its own settings. Inside the sandbox no helper is asked.
- It uses https, http, ssh and git addresses only, and pushes no tags, no submodules and no signature.
- A fetch brings the remote's branches into `origin/<branch>` and the like, by a command inside the sandbox. It changes no
  file of the working tree; merging is the model's own command.
- A branch whose commits add or change files kept with Git LFS is not pushed: CodeZaiku's push does not upload them, so it
  says so and leaves that push to you.
- The remotes are read once per CodeZaiku process: a remote you add is used from the next chat or job.
- Pull requests go through your own `gh`, started from a folder of CodeZaiku's, never the project, on the repository the
  remote's address named when the job began. What it reads was written by other people and the model gets it marked as
  such. Its links can be opened with `web_fetch`; they let no command reach a host.

A commit made by a job's command carries your name and address even when the command passes `-c user.name=…`;
`--author` still sets the author, as git documents it.

What this leaves:

- The address recorded when a job begins is what the project's settings held then. Since a job's change to the
  repository's own settings is put back (next section), that address was set by you, kept by your yes in a chat, written
  by a job before CodeZaiku did this, or written between two runs of CodeZaiku by a program a job left running, which the
  next job's first lines name. The chat's question says whether CodeZaiku has pushed this project there before, and
  `git.push` names the only repositories a job that asks nobody may push to.

## What a job can leave in a repository

The sandbox lets a job's commands write the project's `.git` folder, because commits, branches and tags live there. Some
of what is in that folder is not history but instructions that your own git obeys when you run it in the project, outside
the sandbox: the repository's settings (`.git/config`, which can name programs: a file monitor, an ssh program, a pager,
an alias, a filter, a hook), its hooks (`.git/hooks/`), `.git/info/` (attributes and excludes), `objects/info/alternates`,
the old-style `remotes/` and `branches/`, a linked worktree's pointers and settings, and the same in each submodule's
folder under `.git/modules/` and in a repository a job makes in a folder of the project.

After every command of a job, and every few seconds while one runs, CodeZaiku compares those places with what they were
when the job began and puts back what changed. It keeps what git itself writes in ordinary work: a branch's upstream
(`branch.<name>.remote` naming one of the repository's remotes, and `.merge`), `info/refs`, a new worktree's own folder,
and in a repository the job makes, what `git init` writes. Commits, branches, tags, the index and the logs are the
job's and are never put back.

- Nothing put back is lost. CodeZaiku cannot tell who changed a file, so the changed version is kept in
  `~/.codezaiku/git-put-back/` and named: in the command's result, in the job's last lines and in `gitPutBack` in the
  result of `codezaiku run`. Your own edit made while a job runs is put back the same way, and kept the same way.
- In the chat you are asked about each change after it is put back, with what git on this machine would do with it ("git
  will run … whenever you run git status"). A yes puts that exact change back in place for good.
- What a project shares lives in its own files: `.githooks/`, `.pre-commit-config.yaml`, `.husky/`, `.gitattributes`,
  `.gitignore`, `.gitmodules`. A job edits and commits those like any file. Switching them on for this machine
  (`core.hooksPath`, `pre-commit install`, a filter's program) is yours: say yes in the chat, or do it yourself.
- Nothing of those places is pushed: a push carries commits.
- Reading and putting back are done inside the sandbox, so a job's links (a hooks folder that is a link to a folder of
  yours) cannot make CodeZaiku read or write anything outside what the job's commands could.

What this leaves:

- While a command runs, and up to about four seconds after it writes, a git you run by hand in the project sees the
  change. Run your own git in the project after the job, or between its commands.
- Between two runs of CodeZaiku nothing watches. A program a job left running (a build daemon) is still sandboxed but
  can write then; the next job's first lines say what changed since CodeZaiku last worked here.
- A repository a job makes more than four folders deep, or under `node_modules`, `.venv`, `build`, `target` and the like,
  is not looked for.
- `refs/replace/` changes what `git log` shows for a commit. It is a ref, so it is the job's like any branch.
- The project's remotes and your identity are read when the job begins; nothing of CodeZaiku's reads the changed state
  outside the sandbox.

## The pages of the web chat

A page of the web chat loads nothing from anywhere but CodeZaiku itself. Its scripts are CodeZaiku's own files, and every
page sends a policy that allows no script and no handler written into a page, and no picture, form or request to any
other address. A tag that got into a reply could not run and could not call out.

A reply is shown as text: no tag in it becomes part of the page. A picture a model wrote is shown as a link to the
picture, never loaded. A link a model wrote shows the host it leads to, unless its words already say so. A link to this
machine, to the chat's own address or to a machine your settings name (the model server, the search service) stays
plain words.

Signing out is a button. The same request from another site is refused, as every form of the chat is.

`codezaiku v1` serves no pages, only JSON for another program; its answers say the same: nothing in them is loaded
or run.

## Other MCP servers

What CodeZaiku sends to an MCP server you add (`CODEZAIKU_MCP_SERVERS`, or the servers your editor names over ACP) goes to
that server, and what the model sends it holds only given addresses, as any request does: an address in its arguments
that nobody gave is refused before the server is asked. A field the server calls `address` is the server's own matter (a
street for a map service, a mail address) and is held only when it is written as a web address.

CodeZaiku reads what the server answers. Whether the model may open the addresses in those answers is your switch, server
by server:

```
codezaiku config set mcp.links "search, docs"     # these servers' answers give their addresses
codezaiku config unset mcp.links                   # no server's do
```

It is off for every server until you name it, because a server that computes on what it is sent could hand a model any
address. While it is off, the answer tells the model that its addresses open once you name the server.

## A model on your own machine

A model file is numbers, with one exception: its chat template, a small text the model server follows to lay a conversation
out for the model. `codezaiku model serve install` pins every file it fetches by its checksum. A template can shape the text
the model is given; the server gives it no way to open a file or an address. Installing a model fetches its file from
Hugging Face, the model server's container from GitHub's registry and the small proxy in front of it, llama-swap, from
GitHub.

On Linux, where the model server runs in a container, `codezaiku model serve install` sets it up with no way out to the
network. Its container is on a Docker network that has no route to anything but your machine, with no port of your machine
published and without the right to write raw packets; the proxy reaches it at its own address on that network. The address
CodeZaiku and other programs use for the model, `http://127.0.0.1:8211`, is the proxy's and does not change. So nothing a
model file, a chat template or a fault in the model server does can send anything off your machine. The install tries
this before it finishes: the model has to answer through it, and a request to an outside address, which reaches its target
from an ordinary container, has to fail to connect from inside. When that does not hold on a machine (a firewall, rootless
Docker or another container runtime, no free address range), the install sets the server up the earlier way and says so.

`codezaiku model serve status` says which way your model server is set up, and why. One set up by an earlier version stays
as it is until you run `codezaiku model serve install` again. `codezaiku config set model_network open`, then `model serve
install`, chooses the earlier way on purpose, for a model server that has to reach a network.

What this leaves: the container still reaches your machine itself, at your machine's own address on that network, so a
program on your machine that listens on every address and passes things on (a proxy, a name server) would be a way out.
A program that listens only on `127.0.0.1` is out of its reach. On macOS and Windows the model server is an ordinary
program, not a container, and nothing keeps it from the network.

ResearchZosho sets its own model server up the same way, on a Docker network of its own (`researchzosho-model-shut`) in
other address ranges; CodeZaiku's is `codezaiku-model-shut`. Each program keeps its files in its own folder, CodeZaiku in
`~/.codezaiku` and ResearchZosho in `~/.researchzosho`, and neither writes into the other's. ResearchZosho may use CodeZaiku's
model server by its address. Before its version 0.8.3, a ResearchZosho installed after CodeZaiku kept its files in
`~/.codezaiku` and added its embeddings server to CodeZaiku's model folder. From 0.8.3 it moves its files out by itself at its
next start and takes its entry out of CodeZaiku's model server. Until then `codezaiku doctor` and `model serve status` say it is
there. `model serve status` names what another program put in CodeZaiku's model folder, and says when that can reach the
network. `model serve uninstall` removes only the files CodeZaiku's install made, and leaves the folder when another
program's file is in it.

## A hosted model

When a job runs on a hosted model, the code it reads and everything else it sends the model go to that host.
`codezaiku model list` says for each saved model where that is. A key kept for a host (`codezaiku model key`) is sent to
that host and nowhere else.

Through OpenRouter a model is served by one of several providers, and OpenRouter picks. Every request CodeZaiku sends
tells it which providers to leave out: those based or running in a place CodeZaiku avoids, by OpenRouter's own list of
providers, and those owned by a company based in such a place, by a dated table that ships with the program and names a
public source for each. The providers you name in `openrouter_ignore` are left out as well. OpenRouter's list is read once
a day, without a key, and kept in `~/.codezaiku/openrouter-providers.json`; until the first reading, and whenever a reading
fails, the copy that ships with the program is used. Each job's record names the providers that served its requests, as
OpenRouter reports them (see "What a job cost" in [MODELS.md](docs/MODELS.md)).

When every provider of a model is left out, nothing is sent, and the job, the chat or the result of `codezaiku run` says
so with the setting that allows them. `codezaiku config set avoid_by_owner off` lets the providers that are left out for
their owner alone serve; `codezaiku config set avoid_places none` lets every provider serve.

Hugging Face's router picks a provider too, and a request to it cannot say which places to leave out. `codezaiku model add`
says so when you add such a model, and how to keep it to one provider. A host you name by its address is used as you named
it; when it is run from a place CodeZaiku avoids, `model add` and `model list` say so. They say so too when it is run by a
company registered elsewhere and owned by a company based in such a place (Moonshot's `api.moonshot.ai`, where the host word
`moonshot` points), while `avoid_by_owner` is on.

## The places CodeZaiku avoids

Unless you say otherwise: China with Hong Kong and Macau, Cuba, Iran, North Korea, Russia and Venezuela. These are the
countries of concern of the United States Department of Justice's rule on access to sensitive personal data (28 CFR 202.601,
in effect since 2025-04-08; 28 CFR 202.208 defines China with Hong Kong and Macau). The list has a stated basis so that it is
not one picked by hand; it is yours to change:

```
codezaiku config set avoid_places "CN, HK, MO"     # other places
codezaiku config set avoid_places none             # no place
codezaiku config unset avoid_places                # the list above again
```

ResearchZosho has the same two settings under the same names; each program reads its own.

CodeZaiku runs no search service of its own. Web search goes to Serper, SerpApi or Brave when you set a key for one, else to the
SearXNG named by `searxng` (by default `http://localhost:8888`, where ResearchZosho's search service answers when it is installed;
ResearchZosho's own `avoid_places` leaves the engines of those places out of it), else to Wikipedia and the paper indexes. The
words of a search are sent as words: a model cannot pick the search engine through its search words.

## Verifying a release

Release assets carry `SHA256SUMS` and a per-asset Sigstore bundle, `<asset>.sigstore.json`:

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify codezaiku-X.Y.Z.tar.gz --repo Wyrdsekai/codezaiku \
  --predicate-type https://codezaiku.org/attestation/release/v1
```

For **0.1.0 and 0.1.1 only** (immutable, signed before the predicate moved to a domain we own),
use the original type — these two versions verify with it permanently:

```
  --predicate-type https://codezaiku.dev/attestation/release/v1
```

**The `--predicate-type` is not optional.** `gh attestation verify` defaults to looking for SLSA
build provenance, which we deliberately do not produce (see below), so leaving the flag off prints
`No attestations found with predicate type: https://slsa.dev/provenance/v1` — and still exits `0`.
A correct verification says `Verification succeeded` and names the workflow and tag that signed it.

`gh attestation` needs **GitHub CLI 2.49 or newer**. An older `gh` reports `unknown command` with no hint why — check with `gh --version` before concluding the signature is bad.

Be precise about what that proves. The signature is an **authenticity** statement — the release
workflow, running at that tag, blessed those exact bytes — and deliberately not SLSA build provenance,
because the bytes are built and validated on real hardware before publication rather than by the
runner. The workflow verifies each asset against the release's own checksums before signing and
refuses to sign a mismatch, so a swapped asset cannot pick up a signature. Assets are immutable: a bad
one is replaced by a new version, never re-uploaded under the same name, since a re-upload would
silently invalidate every checksum and signature already in circulation.

## Deployment advice

- Start at `propose`. Move to `guarded` only for fault classes you have watched it handle.
- Set `CODEZAIKU_OPS_AUDIT`, and set `CODEZAIKU_OPS_ALERT_WEBHOOK` if anything runs unattended.
- Give it a dedicated account with the narrowest rights that let it do the job. It will use what it has.
- Prefer key-based ssh; `CODEZAIKU_SSH_PASSWORD` exists for environments that force it.
- Assume anything in a log or an alert may have been written by an attacker — that is how the operator
  treats it.

# Platforms

CodeZaiku is a JVM application, but it works by **shelling out** — to git, build toolchains, docker,
language servers, and a set of Unix utilities. That, not Java, is what decides where it runs.

Every command goes through `bash -lc`.

| Platform | Status |
|---|---|
| **Linux (x86_64, arm64)** | **Measured.** The reference platform; all development happened here. |
| **Docker** | Supported, with one important caveat — see below. |
| **macOS** (arm64) | **Measured.** Coding and research work; parts of operations and security do not. |
| **Windows 11** | **Native** (needs Git for Windows) and **WSL2** — both measured. |

### Acceptance run, all platforms, same day and same model server

`scripts/verify-platform.sh` against one 9B drive, one fixture version, all four runs the same day:

| Platform | passed | failed | skipped |
|---|---|---|---|
| Linux (reference) | 19 | 0 | 0 |
| macOS 26.5 arm64 | 19 | 0 | 0 |
| WSL2 (Ubuntu 26.04) | 19 | 0 | 0 |
| Windows 11 native | 17 | 0 | 1 |

**No failures on any platform.** Linux, macOS and WSL2 are at exact parity, with nothing skipped.
Windows runs one check fewer because its single skip — cancellation reporting — covers three
assertions the other platforms make: a killed run is not delivered a SIGTERM there, so there is
nothing for them to assert. That limitation is described under Windows below.

Getting Windows to this point took real fixes rather than tuned thresholds, and one of them
decides something you should know about: **which shell your commands run in.** See
[Which shell runs your commands](#which-shell-runs-your-commands) below.

Pass an **absolute** `CODEZAIKU_BIN`. The script works inside throwaway repos, so a relative path
stops resolving after the first directory change and every later check reads as a failure — the same
macOS machine scored 5/6/3 that way and full marks with an absolute path. The script now resolves it.
On Windows, point it at the extensionless `codezaiku` launcher and use a POSIX-style path: Git Bash
will not execute `codezaiku.bat`, and does not resolve a `C:\...` path.


---

## Linux

The reference platform. Needs a JDK 21+, and `git`, `curl`, `docker` for the surfaces that use them.
`codezaiku doctor` reports anything missing.

## Docker

```bash
docker build -f packaging/docker/Dockerfile -t codezaiku .
docker run --rm --network host -e CODEZAIKU_DRIVE=http://localhost:8200 codezaiku doctor
```

The image carries bash, git, curl, procps, iproute2, openssl and the docker **client**. State goes on a
`/data` volume so learned cards, research findings and the audit trail outlive the container.

Two limitations to be clear about:

**The coding surface is limited to what the image contains.** CodeZaiku builds and tests the projects it
works on, so it needs their toolchains. The image has none — no Python, Node, Rust or Go. Add what you
need in your own layer, or run CodeZaiku on the host for coding work. Research, MCP and the operator do
not have this problem.

**Operating the host's containers requires mounting the docker socket, which grants effective root on
that host.** CodeZaiku's own posture scan reports a socket mount as a HIGH finding — and when you run
the container that way, it correctly flags *itself*:

```
[POSTURE] [high] docker-socket-mount <this container> — mounts /var/run/docker.sock:
          container can create privileged containers = host takeover  (CIS 5.31)
```

That is not a bug in the scan. If you want the operator to manage a host's stack, running it **on** that
host, or pointing it at a remote target over `ssh://`, is the better shape. Use the image when you want
the CodeZaiku process itself contained and are driving something else.

## macOS

Install from a terminal; there is no `.pkg` and none is needed — CodeZaiku is JVM bytecode, so the
release tarball is the same artifact Linux and Windows use:

```bash
curl -fsSL https://codezaiku.org/install | sh
```

Or unpack the tarball by hand — it runs straight out of it:

```bash
tar xzf codezaiku-0.1.0.tar.gz && ./codezaiku/bin/codezaiku --version
```

Needs a JDK 21+ — `brew install openjdk@21`, or any Temurin build. Nothing is compiled and nothing is
downloaded; building from source instead needs network for the first build. Verified on macOS 26.5
arm64 from a clean unpack.

**Measured on macOS 26.5, Apple Silicon (arm64), JDK 25** — **19 passed, 0 failed, 0 skipped**,
identical to Linux on the same drive: ACP (protocol v1, sessions, error codes, uncorrupted stdout), MCP,
`run`'s JSON contract with the task id echoed, SIGTERM producing a valid interrupted document and
leaving no orphaned children, `doctor` naming what it cannot check, and `secure` rendering once with
its intrusion-detection status. The two failures are the two checks that need the model to finish a
coding task, and were run against a small local model — capability, not platform.

Install from source, `doctor`, the coding surface and the test oracle are all exercised there.
Reasoning about a platform is not the same as running on it — three defects only a real run could
have found, all fixed:

- the build demanded a JDK **21 toolchain exactly**, so a machine holding only JDK 25 — every stock Mac
  with current Temurin — could not build at all. It now compiles with any JDK 21+ and targets 21.
- macOS ships neither `timeout` nor `gtimeout`, so the test oracle's wrapper failed with "command not
  found" and every project reported its suite as FAILED. It now falls back to `perl`'s `alarm`.
- a stock Mac has no `pytest`, and an absent runner was likewise reported as a failing suite rather
  than as "no tests ran".

**Workaround for a LAN drive:** an ssh tunnel makes it local, and `localhost` is exempt from the
permission below — `ssh -N -L 8200:127.0.0.1:8200 user@drive-host`, then point `CODEZAIKU_DRIVE` at
`http://127.0.0.1:8200`. Verified: a full multi-file coding task with a 9B model over that tunnel.

**Local Network permission — the one thing that will catch you.** macOS 14+ gates local-network access
per application, and a JVM started from a terminal (or over ssh) cannot show the permission prompt. A
drive on another machine on your LAN then fails with "No route to host" *while `curl` reaches the same
URL a second later*. Grant your terminal app access under **System Settings → Privacy & Security →
Local Network**. CodeZaiku names this in the error when it happens. A drive on `localhost`, or a remote
host reached over the internet or ssh, is unaffected.

Docker is optional here as everywhere: with the daemon stopped, the coding and research surfaces work
and the container-dependent checks report themselves unavailable.

What does not work on macOS:

| Feature | Why |
|---|---|
| Host memory forecasting | reads `/proc/meminfo`, which does not exist on macOS |
| Disk trend forecasting | uses `df -x`, unsupported by BSD `df` |
| Exposed Docker API check | uses `ss`, a Linux tool |
| Runtime intrusion detection (syscall-level) | Falco is Linux syscall tracing. **Wazuh works here as a shallower substitute** — a maintained ruleset producing named detections from the unified log, file integrity and polled commands; set `CODEZAIKU_OPS_WAZUH_ALERTS`. It sees what was logged, not what the kernel saw. |

Container posture checks, credential checks and the whole operations pipeline against **remote Linux
targets** over `ssh://` work fine — so macOS is a perfectly good workstation from which to operate Linux
servers. It is the local-host sensing that is Linux-specific.

**Guarded remediation is measured on macOS**, against a local Docker stack (a broken cache dependency,
`fix <stack> guarded`). The authority ladder reached GUARDED and acted; blast radius was scoped to the
root container with the bystander protected; the R3 snapshot was captured before acting; the fix failed
its closed-loop verify and was rolled back, ending `verified=false rolledBack=true harmed=false`.

The rollback was exercised harder than intended and held: in one round the model ran
`docker stop && docker rm` on the root service, and R3 recreated it from the snapshot with its original
command line intact. Note what "rolled back" means — the target returns to its **pre-fix** state, so a
run that could not fix the fault leaves the fault in place. That is the designed outcome, not a
failure: undone, not bricked.

That run also exposed two behaviours, neither macOS-specific, both since fixed. The remediation loop
suppresses repeated commands — but "already run" only means "pointless" while nothing has changed, and
once the loop had destroyed the container it suppressed the very command that would recreate it, so
recovery depended entirely on R3. Suppression is now state-relative: a change to the box makes earlier
commands worth running again, while an identical command still cannot immediately repeat itself. And
the `applied:` list was the model's own account of its work, which a run reports only if it finishes
cleanly; the audit now also carries `observed_mutations`, the commands the harness itself watched
change the box, so a destroyed container appears in the record whether or not the model mentioned it.

These announce themselves rather than failing silently — verified on macOS: `secure` reports "no
runtime detection stream — nothing is watching syscalls; absence of alerts is NOT evidence of absence
of intrusion", and `doctor` lists docker, the search backend and the vulnerability scanner as
unavailable rather than omitting them. A check that cannot run says so.

## Windows 11

**Native Windows works, measured on Windows 11 (10.0.26200) with JDK 25** — **17 passed, 0 failed,
1 skipped**; the skip is cancellation reporting, which the platform makes unavailable (below). Native Windows needs no port: the
harness shells out through bash, and Git for Windows supplies one.

**The requirement is Git for Windows** — not MinGit. CodeZaiku shells out through `bash -lc`, and Git
for Windows bundles bash 5.2 with `perl`, `timeout`, `cat`, `grep`, `sed` and `awk`, which is
everything the harness reaches for. That is the normal way git is installed on Windows, and git is
required on every platform anyway. MinGit (the minimal redistributable) ships `sh` and coreutils but
**no bash**, and will not work.

Install from Git Bash — not PowerShell or cmd, which have no `bash` for the scripts:

From PowerShell, once Git for Windows and a JDK 21+ are installed:

```powershell
irm https://codezaiku.org/install.ps1 | iex
```

That verifies the download against the release checksums, unpacks to `%LOCALAPPDATA%\Programs`, and
puts `codezaiku` on your user PATH. Both launchers work: `codezaiku.bat` from PowerShell or cmd, and
the extensionless `codezaiku` from Git Bash. Verified on Windows 11.

By hand instead, from Git Bash:

```bash
export JAVA_HOME=/c/path/to/jdk21-or-newer
export PATH="$JAVA_HOME/bin:$PATH"
tar xzf codezaiku-0.1.0.tar.gz && ./codezaiku/bin/codezaiku --version
```

Verified on Windows 11 from a clean unpack. Use the extensionless `codezaiku`, not `codezaiku.bat`:
Git Bash executes neither a `.bat` nor a `C:\...` path, so scripts want POSIX-style paths
(`/c/Users/...`).

What was verified natively: `--version`, `doctor`, the test oracle, the full `run` verb (single JSON
document on stdout, `taskId` echoed, files written, `files[]` correct and excluding pre-existing
work), ACP (protocol v1, sessions, error codes, uncorrupted stdout), MCP, and `secure` — **17 checks,
no failures**, plus the one skip the platform makes unavoidable (below).

### Which shell runs your commands

CodeZaiku resolves **Git Bash by absolute path** on Windows, and `codezaiku doctor` prints which
shell it settled on:

```
ok   shell: C:\Program Files\Git\bin\bash.exe (native)
```

It has to resolve it explicitly, because asking for `bash` does not get you the one on your PATH.
`C:\Windows\System32\bash.exe` is the **WSL launcher**, and Windows' `CreateProcess` searches
`System32` *before* any PATH directory — so on a machine with WSL installed, a plain `bash` dispatches
every command into the Linux distribution no matter where Git Bash sits on PATH. That is a large
difference to leave to chance: a different interpreter, a different package set, and `/mnt/c` instead
of `C:\`, with nothing announcing it.

If you want WSL, choose it rather than inheriting it — either install CodeZaiku inside the
distribution and treat it as the Linux case, or point `CODEZAIKU_SHELL` at the shell you want:

```bash
CODEZAIKU_SHELL='C:\Windows\System32\bash.exe'   # dispatch into WSL from a native install
```

`doctor` reports that choice too, and warns when the resolved shell is the WSL launcher.


**Two limitations, both measured:**

- **On the CLI path only, a killed run reports nothing.** On Linux and macOS a `SIGTERM` produces the
  usual result document with `"interrupted": true` and the files written so far. Windows has no
  SIGTERM; MSYS emulates POSIX signals only between MSYS processes, so a native `java.exe` never
  receives one and the shutdown hook never runs. Measured: exit 143, zero bytes on stdout. `taskkill`
  is no better — without `/F` it sends `WM_CLOSE`, which a console JVM does not act on, and with `/F`
  it is an immediate kill. There is no graceful signal to deliver on Windows.

  **ACP is unaffected, and is the answer if you need cancellation here.** `session/cancel` is a
  protocol message rather than a signal, so it behaves exactly as on Linux — measured on Windows:
  `stopReason: "cancelled"`, `"interrupted": true`, and the six files written before the cancel all
  reported in `files[]`, matching disk. A host that can cancel in-protocol loses nothing.
- **Operations and security sensing is Linux-only**, as on macOS — no `systemctl`, `journalctl`, `ss`
  or `/proc`. Container posture checks work if Docker Desktop is installed, and operating **remote
  Linux hosts over `ssh://`** works fine.

Paths: a native ACP client sends `C:\Users\you\project` and works. A client launched from Git Bash
sending the MSYS form `/c/Users/...` is rejected — a Windows JVM cannot resolve it — and the error now
says so explicitly.

### WSL2

Supported and simpler than native: install inside the WSL distribution and treat it as the Linux case.

**Measured** on Ubuntu 26.04 under WSL2 (kernel 6.18-microsoft-standard-WSL2, JDK 21):
`scripts/verify-platform.sh` returned **19 passed, 0 failed, 0 skipped — identical to Linux**, with a
model server attached and the full oracle, coding, cancellation, protocol and security checks running.

Two things still worth knowing, neither of which the run contradicted:

- Keep projects in the WSL filesystem (`~/…`), not under `/mnt/c` — that path is slow and
  case-insensitive.
- WSL2 sits behind a virtual NIC, so a model server on the Windows host is not `localhost` from
  inside the distribution. Point `CODEZAIKU_DRIVE` at the host's WSL-visible address, or run the
  server inside WSL2.
- systemd is off in some distributions, which affects only the operations surface's use of
  `systemctl` and `journalctl`.

Docker Desktop with the WSL2 backend also works: build and run the image as in the Docker section.

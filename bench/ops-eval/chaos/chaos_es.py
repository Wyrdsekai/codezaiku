"""CodeZaiku × Chaos-Toolkit ES scenario — validate the elasticsearch-diskwatermark card on a
do-and-verify oracle WE author, blind-by-construction.

Engines like Elasticsearch have no public incident benchmark (SUBSTRATE.md), so we build the oracle the
Chaos-Toolkit way: a STEADY-STATE hypothesis evaluated BEFORE and AFTER a real injected fault. Here:
  fault   = the data node's disk genuinely fills past the 95% flood-stage watermark (REAL data, es_fill.py)
            -> ES flips every index to read_only_allow_delete -> writes fail. Nothing mocked.
  oracle  = the engine's OWN objective API: a write to cp-probe returns 2xx AND cluster health != red.
  agent   = blind. It sees the incident text + whatever it inspects; it never sees how disk got full, and
            the oracle rewards "writes work again" (free disk / grow volume / delete indices) — not any one
            command. A lazy "clear the block only" fix re-trips within ~30s on a still-full node, so the
            oracle enforces the REAL remediation.
Same model both arms; CP_KNOWLEDGE on/off is the only difference (the deferred-scan card push, identical
mechanism to the SREGym driver: two-shot scan, match-gate, cap-to-one card, immediate/rescue policy).
"""
import glob, json, os, re, subprocess, sys, time
import requests

BASE = os.environ.get("AGENT_API_BASE", "http://localhost:8200").rstrip("/")
MODEL = os.environ.get("AGENT_MODEL_ID", "default")
API_KEY = os.environ.get("AGENT_API_KEY", "dummy")
KNOWLEDGE_DIR = os.environ.get("CP_KNOWLEDGE", "")
MAX_STEPS = int(os.environ.get("CP_MAX_STEPS", "20"))
PUSH_MODE = os.environ.get("CP_PUSH_MODE", "card").lower()
SIG_SCAN_TURN = int(os.environ.get("CP_SIG_SCAN_TURN", "4"))
RESCUE_TURN = int(os.environ.get("CP_RESCUE_TURN", "14"))
MAX_OBS = 2500
CTX_BUDGET = 24000

ES = os.environ.get("CP_ES_URL", "http://localhost:19200").rstrip("/")
CONTAINER = os.environ.get("CP_ES_CONTAINER", "cp-es")
FILL = os.environ.get("CP_FILL_SCRIPT", ""${CP_WORK:-/opt/codezaiku}"/chaos/es_fill.py")


# ---------- objective oracle (engine's own API) ----------
def _disk_pct():
    try:
        out = subprocess.check_output(
            ["docker", "exec", CONTAINER, "df", "-P", "/usr/share/elasticsearch/data"],
            timeout=15).decode().splitlines()[1].split()
        return int(out[4].rstrip("%"))
    except Exception:
        return 100


def steady_state():
    """Functional do-and-verify on the engine's OWN API. Three conditions, ALL required:
      1. a write succeeds (the flood-stage read-only block is gone),
      2. cluster health is not red,
      3. the protected `orders` canary still holds its pre-fault value (total==42).
    (3) fails a destructive "delete all data + restart" (empty ES would pass 1+2). This is the check used
    to detect the fault at reset; the final grade (see main) ALSO requires disk to be genuinely freed.
    Returns (ok, detail)."""
    try:
        r = requests.post(f"{ES}/cp-probe/_doc", json={"t": int(time.time())}, timeout=10)
        write_ok = r.status_code in (200, 201)
    except Exception as e:
        return False, f"write-exc:{e}"
    try:
        h = requests.get(f"{ES}/_cluster/health", timeout=10).json().get("status", "red")
    except Exception as e:
        return False, f"health-exc:{e}"
    try:
        d = requests.get(f"{ES}/orders/_doc/1", timeout=10).json()
        canary_ok = bool(d.get("found")) and d.get("_source", {}).get("total") == 42
    except Exception as e:
        return False, f"canary-exc:{e}"
    return (write_ok and h != "red" and canary_ok), f"write={r.status_code} health={h} canary={canary_ok}"


# ---------- deferred-scan knowledge (identical mechanism to the SREGym driver) ----------
def load_sig_cards():
    out = []
    for f in sorted(glob.glob(os.path.join(KNOWLEDGE_DIR, "*.md"))):
        try:
            lines = open(f).read().split("\n")
        except Exception:
            continue
        if not lines or not lines[0].lower().startswith("match:"):
            continue
        sig, push, body = None, "rescue", lines[1:]
        while body and (body[0].lower().startswith(("signature:", "push:", "status:"))):
            h = body[0].lower()
            if h.startswith("signature:"):
                sig = [k.strip() for k in body[0][10:].lower().split(",") if k.strip()]
            elif h.startswith("push:"):
                push = body[0][5:].strip().lower()
            body = body[1:]
        if sig:
            mk = [k.strip() for k in lines[0][6:].lower().split(",") if k.strip()]
            out.append((mk, sig, push, "\n".join(body).strip()))
    return out


# The agent gets a host shell; this box also runs the model servers + other benchmarks. Keep it scoped to
# the ES incident: block references to other critical containers and docker stop/rm/kill/restart of anything
# that is not cp-es. (`docker restart cp-es` stays allowed — a legit SRE action.)
_PROTECTED = ("sregym", "codezaiku-", "codezaiku_", "cp-embed", "kind-", "kind_") + tuple(
    n.strip().lower() for n in os.environ.get("CP_PROTECTED", "").split(",") if n.strip())
_DOCKER_MUT = re.compile(r"docker\s+(stop|rm|kill|restart)\s+(?!cp-es\b)", re.IGNORECASE)


def sh(cmd, t=20):
    low = cmd.lower()
    if any(p in low for p in _PROTECTED) or _DOCKER_MUT.search(cmd):
        return "error: command out of scope for this incident (only the cp-es Elasticsearch container is in scope)"
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=t)
        return (r.stdout or "") + (("\n" + r.stderr) if r.stderr else "")
    except Exception as e:
        return f"error: {e}"


def stack_text():
    """Environment identity for match-kw gating: we know it's an Elasticsearch container on this host."""
    return f"elasticsearch elastic docker {CONTAINER}".lower()


def scan_logs():
    """Haystack = the ES container LOGS + an objective STATE bundle (the disk-full class can be log-quiet if
    the watermark line rotated out, so the mount usage + read_only block state join the haystack)."""
    text = sh(f"docker logs --tail 120 {CONTAINER} 2>&1").lower()
    state = sh(f"docker exec {CONTAINER} df -h /usr/share/elasticsearch/data 2>&1")
    try:
        s = requests.get(f"{ES}/cp-probe/_settings?flat_settings", timeout=8).text
        state += "\n" + s
    except Exception:
        pass
    try:
        state += "\n" + requests.get(f"{ES}/_cat/allocation?v", timeout=8).text
    except Exception:
        pass
    return text + "\n" + state.lower()


# ---------- ReAct loop ----------
SYS = (
    "You are a senior SRE operating a host that runs an Elasticsearch container. Investigate the reported "
    "failure, ACT to fix its ROOT cause, and verify the fix took effect.\n\n"
    f"ENVIRONMENT: the container is named `{CONTAINER}`. Elasticsearch's REST API is reachable at "
    f"{ES} (no auth). Run shell on the host: use `curl` for the ES API, `docker exec {CONTAINER} <cmd>` "
    "for work inside the container (df, du, ls, rm).\n\n"
    "RULES: one action per turn, a SINGLE shell command. Inspect (curl the ES API, docker exec df/du), form "
    "a hypothesis, then run the command that FIXES the root cause. Re-check that writes succeed and cluster "
    "health is green/yellow.\n\n"
    "Output EXACTLY ONE line per turn, either:\n"
    "  ACTION: <one shell command>\n"
    "  DONE: <one-sentence description of the fix>   (only once writes succeed again)\n"
)
ACTION_RE = re.compile(r"ACTION:\s*(.+)", re.IGNORECASE)
DONE_RE = re.compile(r"DONE:\s*(.*)", re.IGNORECASE | re.DOTALL)


def chat(history):
    r = requests.post(f"{BASE}/v1/chat/completions",
                      headers={"Authorization": f"Bearer {API_KEY}"},
                      json={"model": MODEL, "messages": history, "temperature": 0, "max_tokens": 512},
                      timeout=180)
    return r.json()["choices"][0]["message"]["content"]


def trim(history):
    while sum(len(m["content"]) for m in history) > CTX_BUDGET and len(history) > 4:
        for i in range(2, len(history) - 2):
            if history[i]["role"] == "user" and len(history[i]["content"]) > 200:
                history[i]["content"] = "[earlier observation trimmed]"
                break
        else:
            break


def agent_loop():
    history = [{"role": "system", "content": SYS},
               {"role": "user", "content": "INCIDENT\nApplication: search cluster (Elasticsearch)\n"
                "Description: indexing/write requests to Elasticsearch are failing. The cluster serves live "
                "application data (the `orders` index) that must NOT be lost — do not wipe the node or delete "
                "all data. Investigate and fix the root cause now."}]
    sig_cards = load_sig_cards() if KNOWLEDGE_DIR else []
    if sig_cards:
        print(f"[chaos] {len(sig_cards)} signature card(s) armed", flush=True)
    rescue_hits = []
    dump_path = os.environ.get("CP_LOG_DUMP", "")

    for turn in range(1, MAX_STEPS + 1):
        if turn == SIG_SCAN_TURN and dump_path and not sig_cards:
            open(dump_path, "w").write(scan_logs())
            print(f"[chaos] LOG_DUMP written -> {dump_path}", flush=True)
        if sig_cards and turn in (SIG_SCAN_TURN, SIG_SCAN_TURN + 4):
            logs = scan_logs()
            stk = stack_text()
            best = None
            for mk, sig, push, body in sig_cards:
                if not any(k in stk for k in mk):
                    continue
                hits = sum(1 for k in sig if k in logs)
                if hits == 0:
                    continue
                eff = push if PUSH_MODE == "card" else PUSH_MODE
                if best is None or hits > best[0]:
                    best = (hits, eff, body)
            now, held = [], []
            if best:
                (now if best[1] == "immediate" else held).append(best[2])
            if now or held or turn == SIG_SCAN_TURN + 4:
                sig_cards = []
            if now:
                kb = "## OPS KNOWLEDGE (matched this fault signature — fix procedure)\n\n" + "\n\n".join(now)
                history.append({"role": "user", "content": kb})
                print(f"[chaos] KNOWLEDGE PUSHED (immediate, turn {turn}, {len(kb)} chars)", flush=True)
            elif held:
                rescue_hits = held
                print(f"[chaos] KNOWLEDGE HELD for rescue (turn {RESCUE_TURN})", flush=True)
            else:
                print(f"[chaos] scan turn {turn}: no signature match ({len(logs)} chars)", flush=True)
        if turn >= RESCUE_TURN and rescue_hits:
            kb = "## OPS KNOWLEDGE (matched this fault signature — fix procedure)\n\n" + "\n\n".join(rescue_hits)
            rescue_hits = []
            history.append({"role": "user", "content": kb})
            print(f"[chaos] KNOWLEDGE PUSHED (rescue, turn {turn})", flush=True)

        trim(history)
        try:
            out = chat(history)
        except Exception as e:
            out = f"ACTION: curl -s {ES}/_cluster/health   (drive error: {e})"
        history.append({"role": "assistant", "content": out})

        m_done = DONE_RE.search(out)
        m_act = ACTION_RE.search(out)
        if m_done and not m_act:
            print(f"[chaos] DONE at turn {turn}: {m_done.group(1).strip()[:80]}", flush=True)
            return
        if not m_act:
            history.append({"role": "user", "content": "Reply with exactly one line: 'ACTION: <shell>' or 'DONE: <fix>'."})
            continue
        cmd = m_act.group(1).strip().strip("`")
        print(f"[chaos] turn {turn} ACTION: {cmd[:110]}", flush=True)
        obs = sh(cmd)
        if len(obs) > MAX_OBS:
            obs = obs[:MAX_OBS] + "\n[...truncated]"
        history.append({"role": "user", "content": obs})
    print("[chaos] budget reached", flush=True)


# ---------- orchestration ----------
def _es_healthy(timeout=90):
    start = time.time()
    while time.time() - start < timeout:
        try:
            if requests.get(f"{ES}/_cluster/health", timeout=5).status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(5)
    return False


def reset_fault(target_df=35, wait=180):
    """Restart the container to a PRISTINE, healthy ES (tmpfs wipes clean; this prevents cross-run node-state
    corruption from a prior agent's rm-rf/restart), then rebuild the fault (real Lucene data to ~target_df%
    with the watermark set just below it -> genuine flood-stage read-only), then wait until the oracle
    actually FAILS (the disk-monitor applies the block ~30s after the watermark is set)."""
    print("[chaos] restarting ES container for a clean node...", flush=True)
    subprocess.run(f"docker restart {CONTAINER}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if not _es_healthy():
        print("[chaos] WARNING ES not healthy after restart", flush=True)
        return False
    print(f"[chaos] resetting fault (fill to df {target_df}%)...", flush=True)
    env = dict(os.environ, TARGET_DF=str(target_df))
    try:
        subprocess.run([sys.executable, FILL], env=env, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as e:
        print(f"[chaos] fill failed rc={e.returncode}", flush=True)
        return False
    start = time.time()
    while time.time() - start < wait:
        ok, detail = steady_state()
        if not ok:
            print(f"[chaos] fault applied ({detail})", flush=True)
            return True
        time.sleep(6)
    print(f"[chaos] WARNING fault did not apply within {wait}s (last: {detail})", flush=True)
    return False


def main():
    run_id = os.environ.get("CP_RUN_ID", str(int(time.time())))
    arm = "ON" if KNOWLEDGE_DIR else "OFF"
    if os.environ.get("CP_SKIP_RESET") != "1":
        if not reset_fault():
            print("[chaos] RESULT run=%s arm=%s result=NO-FAULT" % (run_id, arm), flush=True)
            return
    before_ok, before = steady_state()
    fault_disk = _disk_pct()
    print(f"[chaos] steady-state BEFORE agent: ok={before_ok} ({before}) fault_disk={fault_disk}%", flush=True)
    if before_ok:
        print("[chaos] RESULT run=%s arm=%s result=NO-FAULT (steady before agent)" % (run_id, arm), flush=True)
        return

    t0 = time.time()
    agent_loop()
    # settle: ES auto-clears the read-only block ~30s after disk drops; poll the functional oracle briefly.
    func_ok = False
    for _ in range(8):
        func_ok, after = steady_state()
        if func_ok:
            break
        time.sleep(6)
    # Final grade also requires the disk to be GENUINELY FREED (>=8pp below the fault occupancy). This is
    # load-bearing: it fails the two shortcuts that regain writes WITHOUT fixing root cause — clearing the
    # read-only block (re-trips) and raising the watermark (disk still full) — so PASS means real relief.
    after_disk = _disk_pct()
    disk_freed = after_disk <= fault_disk - 8
    after_ok = func_ok and disk_freed
    dur = int(time.time() - t0)
    result = "PASS" if after_ok else "FAIL"
    print(f"[chaos] steady-state AFTER agent: func_ok={func_ok} disk {fault_disk}%->{after_disk}% "
          f"freed={disk_freed} => {result} dur={dur}s", flush=True)
    print(f"[chaos] RESULT run={run_id} arm={arm} result={result} dur={dur}s", flush=True)
    csv = os.environ.get("CP_RESULT_CSV", "")
    if csv:
        with open(csv, "a") as fh:
            fh.write(f"{run_id},{arm},{result},{dur}\n")


if __name__ == "__main__":
    main()

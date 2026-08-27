"""CodeZaiku × Chaos-Toolkit — shared harness core for validating ops cards on engines with no incident
benchmark (SUBSTRATE.md). One do-and-verify oracle pattern, many engines: a per-engine SCENARIO module
supplies the fault injector + the engine's-own-API oracle + the incident/prompt; this core supplies the
ReAct loop, the deferred card-scan push (identical to the SREGym driver: two-shot scan, match-gate,
at-most-one card, immediate/rescue), the reset/grade orchestration, and the CSV.

Blind-by-construction discipline (guards the self-fixture trap): the scenario's fault must be REAL, its
oracle must be the engine's OWN API, its grade() must reject the regain-access-without-fixing shortcuts, and
the agent never sees the injection. Same model both arms; CP_KNOWLEDGE on/off is the only difference.

A scenario module (selected by CP_SCENARIO, e.g. "scenario_rabbitmq") must define:
  CONTAINER   str                  - the engine container name (also the only container the shell may mutate)
  SYS         str                  - system prompt (senior SRE + this engine's environment)
  INCIDENT    str                  - the incident description shown to the agent
  healthy()   -> bool              - True once the engine answers its API after a restart
  reset()     -> bool              - restart container to a pristine node + inject the real fault; return
                                     True once the fault is applied (steady_ok() is False). Stash any
                                     baseline it needs for grade().
  steady_ok() -> (bool, str)       - functional oracle (engine's own API); used to detect the fault
  grade()     -> (bool, str)       - final PASS check: functional recovery AND genuine root-cause relief
                                     (must fail the shortcuts that regain access without fixing)
  stack_text()-> str               - environment identity for match-kw gating
  scan_logs() -> str               - engine logs + state bundle (the card-signature haystack)
"""
import glob, importlib, os, re, subprocess, time
import requests

BASE = os.environ.get("AGENT_API_BASE", "http://localhost:8200").rstrip("/")
MODEL = os.environ.get("AGENT_MODEL_ID", "default")
API_KEY = os.environ.get("AGENT_API_KEY", "dummy")
KNOWLEDGE_DIR = os.environ.get("CP_KNOWLEDGE", "")
MAX_STEPS = int(os.environ.get("CP_MAX_STEPS", "22"))
PUSH_MODE = os.environ.get("CP_PUSH_MODE", "card").lower()
SIG_SCAN_TURN = int(os.environ.get("CP_SIG_SCAN_TURN", "4"))
RESCUE_TURN = int(os.environ.get("CP_RESCUE_TURN", "16"))
MAX_OBS = 2500
CTX_BUDGET = 24000

scn = importlib.import_module(os.environ.get("CP_SCENARIO", "scenario_es"))

# ---------- scoped host shell (agent action surface) ----------
# CodeZaiku's own services and the local benchmark clusters. Name THIS box's model-server and infra
# containers in CP_PROTECTED (comma-separated) rather than editing the tuple — hardcoding one
# operator's container names ships them to everyone who clones this.
_PROTECTED = ("sregym", "codezaiku-", "codezaiku_", "cp-embed", "kind-", "kind_") + tuple(
    n.strip().lower() for n in os.environ.get("CP_PROTECTED", "").split(",") if n.strip())
_DOCKER_MUT = re.compile(r"docker\s+(stop|rm|kill|restart)\s+(?!" + re.escape(scn.CONTAINER) + r"\b)", re.I)


def sh(cmd, t=20):
    low = cmd.lower()
    if any(p in low for p in _PROTECTED if p != scn.CONTAINER) or _DOCKER_MUT.search(cmd):
        return f"error: command out of scope (only the {scn.CONTAINER} container is in scope for this incident)"
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=t)
        return (r.stdout or "") + (("\n" + r.stderr) if r.stderr else "")
    except Exception as e:
        return f"error: {e}"


scn.sh = sh  # scenarios reuse the same scoped shell

# ---------- deferred-scan knowledge ----------
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
        while body and body[0].lower().startswith(("signature:", "push:", "status:")):
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


# ---------- ReAct loop ----------
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
    history = [{"role": "system", "content": scn.SYS},
               {"role": "user", "content": scn.INCIDENT}]
    sig_cards = load_sig_cards() if KNOWLEDGE_DIR else []
    if sig_cards:
        print(f"[chaos] {len(sig_cards)} signature card(s) armed", flush=True)
    rescue_hits = []
    dump_path = os.environ.get("CP_LOG_DUMP", "")

    for turn in range(1, MAX_STEPS + 1):
        if turn == SIG_SCAN_TURN and dump_path and not sig_cards:
            open(dump_path, "w").write(scn.scan_logs())
            print(f"[chaos] LOG_DUMP written -> {dump_path}", flush=True)
        if sig_cards and turn in (SIG_SCAN_TURN, SIG_SCAN_TURN + 4):
            logs = scn.scan_logs()
            stk = scn.stack_text()
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
            out = f"ACTION: echo drive-error {e}"
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
def main():
    run_id = os.environ.get("CP_RUN_ID", str(int(time.time())))
    arm = "ON" if KNOWLEDGE_DIR else "OFF"
    if os.environ.get("CP_SKIP_RESET") != "1":
        if not scn.reset():
            print(f"[chaos] RESULT run={run_id} arm={arm} result=NO-FAULT", flush=True)
            return
    before_ok, before = scn.steady_ok()
    print(f"[chaos] steady-state BEFORE agent: ok={before_ok} ({before})", flush=True)
    if before_ok:
        print(f"[chaos] RESULT run={run_id} arm={arm} result=NO-FAULT (steady before agent)", flush=True)
        return

    t0 = time.time()
    agent_loop()
    ok = False
    for _ in range(8):                      # settle: some engines clear the alarm/block a few s after relief
        ok, detail = scn.grade()
        if ok:
            break
        time.sleep(6)
    dur = int(time.time() - t0)
    result = "PASS" if ok else "FAIL"
    print(f"[chaos] steady-state AFTER agent: {detail} => {result} dur={dur}s", flush=True)
    print(f"[chaos] RESULT run={run_id} arm={arm} result={result} dur={dur}s", flush=True)
    csv = os.environ.get("CP_RESULT_CSV", "")
    if csv:
        with open(csv, "a") as fh:
            fh.write(f"{run_id},{arm},{result},{dur}\n")


if __name__ == "__main__":
    main()

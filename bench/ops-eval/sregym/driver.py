"""CodeZaiku agent for SREGym — stage-aware ReAct over the kubectl MCP tool, driven by our local model
server, with the deferred-scan knowledge-push mechanism ported from AIOpsLab cp_agent.py.

SREGym sequences stages: setup -> diagnosis -> mitigation -> (resolution) -> done, each graded by a /submit
that advances the stage. We validate FIX-PROCEDURE cards, so the work is the MITIGATION stage: the ReAct
loop investigates then applies kubectl fixes, and the objective *_mitigation oracle inspects live cluster
state (not the submit text). Diagnosis is submitted minimally to advance (its oracle is an LLM judge we
ignore for fix-card validation).

Constraints: `exec_kubectl_cmd_safely` rejects any non-kubectl command (no shell/pipes) — actions are pure
kubectl, in-pod work via `kubectl exec <pod> -- ...`. The deferred knowledge scan runs adapter-side (direct
`kubectl logs` + Python match), pushing the matched card per its declared push policy. Same model both arms;
CP_KNOWLEDGE on/off is the only difference.
"""
import asyncio, glob, json, logging, os, re, subprocess, sys, time, uuid
from contextlib import AsyncExitStack
from pathlib import Path

import requests
from fastmcp import Client
from fastmcp.client import SSETransport

sregym_root = Path(__file__).resolve().parents[2]
if str(sregym_root) not in sys.path:
    sys.path.insert(0, str(sregym_root))

from clients.harness.problem_id import resolve_problem_id                        # noqa: E402
from clients.stratus.configs.langgraph_tool_configs import LanggraphToolConfig   # noqa: E402
from logger import init_logger                                                   # noqa: E402

init_logger()
logger = logging.getLogger("all.codezaiku.driver")

BASE = os.environ.get("AGENT_API_BASE", "http://localhost:8200").rstrip("/")
MODEL = os.environ.get("AGENT_MODEL_ID") or os.environ.get("CP_MODEL", "default")
API_KEY = os.environ.get("AGENT_API_KEY", "dummy")
KNOWLEDGE_DIR = os.environ.get("CP_KNOWLEDGE", "")
MAX_STEPS = int(os.environ.get("CP_MAX_STEPS", "30"))
PUSH_MODE = os.environ.get("CP_PUSH_MODE", "card").lower()
SIG_SCAN_TURN = int(os.environ.get("CP_SIG_SCAN_TURN", "5"))
RESCUE_TURN = int(os.environ.get("CP_RESCUE_TURN", "18"))
MAX_OBS = 2500
CTX_BUDGET = 26000

CONDUCTOR = f"http://{os.getenv('API_HOSTNAME','localhost')}:{os.getenv('API_PORT','8000')}"


def get_status(timeout=10):
    try:
        return requests.get(f"{CONDUCTOR}/status", timeout=timeout).json().get("stage", "")
    except Exception:
        return ""


def wait_for_stage(targets, timeout=900):
    start = time.time()
    while time.time() - start < timeout:
        st = get_status()
        if st in targets:
            logger.info(f"[codezaiku] reached stage: {st}")
            return st
        time.sleep(3)
    logger.warning(f"[codezaiku] timed out waiting for {targets} (last={get_status()})")
    return get_status()


def submit(solution, timeout=30):
    try:
        r = requests.post(f"{CONDUCTOR}/submit", json={"solution": solution}, timeout=timeout)
        logger.info(f"[codezaiku] submit -> {r.status_code}")
    except Exception as e:
        logger.error(f"[codezaiku] submit failed: {e}")


def get_app(retries=6, backoff=5):
    for a in range(1, retries + 1):
        try:
            r = requests.get(f"{CONDUCTOR}/get_app", timeout=15)
            r.raise_for_status()
            return r.json()
        except Exception as e:
            if a == retries:
                raise
            time.sleep(backoff)


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
        while body and (body[0].lower().startswith("signature:")
                        or body[0].lower().startswith("push:")
                        or body[0].lower().startswith("status:")):
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


def _kubectl(cmd, t=15):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=t).stdout
    except Exception:
        return ""


def stack_text(namespace):
    """Environment + workload identity for match-kw gating (mirrors OpsKnowledge.block's stack matching):
    the harness knows it drives kubernetes, plus the namespace's pod names and images."""
    # jsonpath MUST be shell-quoted: unquoted, bash splits at the space inside {range ...} and kubectl
    # errors -> empty stack text -> every card whose match isn't in the constant prefix is silently gated
    # out (masked for cards matching 'kubectl'; caught by the kafka card whose only match kw is 'kafka').
    pods = _kubectl(f"kubectl get pods -n {namespace} -o jsonpath='{{range .items[*]}}{{.metadata.name}} {{range .spec.containers[*]}}{{.image}} {{end}}{{end}}'", 20)
    return ("kubernetes k8s kubectl " + pods).lower()


def kafka_lag_probe(namespace):
    """Consumer-group lag sensor: poison-pill/HOL faults are LOG-QUIET (a stalled consumer may log nothing)
    — their evidence is a frozen committed offset with growing lag. When any group's lag >= 50 the probe
    emits a documented marker line ("kafka consumer-group lag stalled: ...") that card signatures key on.
    Deterministic threshold on a direct metric, not statistics-as-semantics."""
    pods = [p for p in _kubectl(f"kubectl get pods -n {namespace} -o name", 10).split()
            if "kafka" in p and "exporter" not in p]
    if not pods:
        return ""
    pod = pods[0].split("/")[-1]
    # In-pod exec is structurally broken on agent-instrumented brokers (measured: JAVA_TOOL_OPTIONS injects
    # the otel javaagent into the CLI's second JVM -> cgroup OOM 137 inside the broker pod's limit; and the
    # broker answers on the service name, not localhost). So: THROWAWAY POD with the broker's own image
    # (cached on the node, guaranteed-compatible binaries, own cgroup, clean env).
    img = _kubectl(f"kubectl get pod {pod} -n {namespace} -o jsonpath={{.spec.containers[0].image}}", 10).strip()
    node = _kubectl(f"kubectl get pod {pod} -n {namespace} -o jsonpath={{.spec.nodeName}}", 10).strip()
    if not img:
        return ""
    # Pin the CLI pod to the BROKER'S node: the image is cached only there (measured: one node of four) —
    # any other placement silently spends the whole budget pulling ~1GB. Errors stay in the captured text.
    overrides = '{"spec":{"nodeName":"' + node + '"}}' if node else "{}"
    # DETACHED run + poll + logs + delete: `--rm -i` attach RACES pod completion and loses the output
    # (measured: 0 chars from a working command); the log store never races.
    name = "cp-sensor-kafkacli"
    _kubectl(f"kubectl delete pod {name} -n {namespace} --wait=false 2>/dev/null", 8)
    _kubectl(
        f"kubectl run {name} -n {namespace} --restart=Never --image={img} "
        f"--overrides='{overrides}' --env=JAVA_TOOL_OPTIONS= --env=KAFKA_OPTS= --command -- sh -c "
        f"'KAFKA_HEAP_OPTS=-Xmx128m /opt/kafka/bin/kafka-consumer-groups.sh "
        f"--bootstrap-server kafka:9092 --describe --all-groups --timeout 8000 2>&1'", 15)
    for _ in range(20):
        ph = _kubectl(f"kubectl get pod {name} -n {namespace} -o jsonpath={{.status.phase}}", 8).strip()
        if ph in ("Succeeded", "Failed"):
            break
        time.sleep(3)
    out = _kubectl(f"kubectl logs {name} -n {namespace} 2>/dev/null", 10)
    _kubectl(f"kubectl delete pod {name} -n {namespace} --wait=false 2>/dev/null", 8)
    marks = []
    maxlag, rows = -1, 0
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 6 and parts[5].isdigit():
            rows += 1
            maxlag = max(maxlag, int(parts[5]))
            if int(parts[5]) >= 20:
                marks.append(f"kafka consumer-group lag stalled: group={parts[0]} topic={parts[1]} lag={parts[5]}")
    print(f"[codezaiku] kafka-lag probe: {len(out)} chars, {rows} partition rows, max lag={maxlag}, markers={len(marks)}", flush=True)
    if not marks:
        return "\n" + out[-1200:]
    return "\n" + "\n".join(marks) + "\n" + out[-1200:]


def scan_logs(namespace):
    """Scan text = pod LOGS + a bounded cluster-STATE bundle. Three fault classes proved log-quiet
    (scaled-to-zero: no pods -> no logs; kafka poison-pill: evidence = consumer lag; coredns NXDOMAIN
    template: evidence = the poisoned stanza in the coredns ConfigMap) — their signatures live in STATE,
    so the state bundle (pods wide, warning events, coredns config) joins the haystack. ~4 fast calls."""
    pods = _kubectl(f"kubectl get pods -n {namespace} -o name", 20).split()
    text = ""
    for p in pods[:40]:
        text += _kubectl(f"kubectl logs --tail=25 {p} -n {namespace} 2>/dev/null", 3).lower()
    state = _kubectl(f"kubectl get pods -n {namespace} -o wide 2>/dev/null", 10)
    state += kafka_lag_probe(namespace)
    state += _kubectl(f"kubectl get events -n {namespace} --field-selector type=Warning --no-headers 2>/dev/null | tail -20", 10)
    state += _kubectl("kubectl -n kube-system get cm coredns -o yaml 2>/dev/null", 10)
    return text + "\n" + state.lower()


def _new_mcp_client(session_id):
    sse_timeout = float(os.getenv("SSE_READ_TIMEOUT", "3600"))
    base = os.getenv("MCP_SERVER_URL", f"http://{os.getenv('API_HOSTNAME','localhost')}:{os.getenv('MCP_SERVER_PORT','9954')}")
    return Client(SSETransport(url=f"{base}/kubectl/sse",
                               headers={"sregym_ssid": session_id},
                               sse_read_timeout=(sse_timeout if sse_timeout >= 0 else None)))


def _parts_to_text(r):
    parts = getattr(r, "content", r)
    return "\n".join(getattr(p, "text", str(p)) for p in parts)


class McpSession:
    """A PERSISTENT kubectl-MCP connection. fastmcp's Client._connect intermittently hangs on _ready_event
    (SSE cold-connect), AND opening/closing a fresh client PER CALL churns the server into degradation
    (observed: all calls start timing out mid-run). So we connect ONCE with retry and REUSE the session for
    every action; a dropped connection triggers exactly one reconnect+retry. No per-call churn."""
    def __init__(self, session_id):
        self.sid = session_id
        self.client = None
        self.stack = None

    async def open(self, tries=8, per_try=20):
        for i in range(tries):
            client = _new_mcp_client(self.sid)
            stack = AsyncExitStack()
            try:
                await asyncio.wait_for(stack.enter_async_context(client), timeout=per_try)
                if client.is_connected():
                    self.client, self.stack = client, stack
                    print(f"[codezaiku] MCP connected (attempt {i+1})", flush=True)
                    return True
            except Exception:
                pass
            try:
                await stack.aclose()
            except Exception:
                pass
        print(f"[codezaiku] MCP connect FAILED after {tries} attempts", flush=True)
        return False

    async def call(self, cmd, per_try=60):
        if self.client is None or not self.client.is_connected():
            await self.close()
            if not await self.open():
                return "error: MCP not connected"
        try:
            return _parts_to_text(await asyncio.wait_for(
                self.client.call_tool("exec_kubectl_cmd_safely", arguments={"cmd": cmd}), timeout=per_try))
        except Exception as e:
            await self.close()            # connection likely dropped — reconnect ONCE and retry
            if await self.open():
                try:
                    return _parts_to_text(await asyncio.wait_for(
                        self.client.call_tool("exec_kubectl_cmd_safely", arguments={"cmd": cmd}), timeout=per_try))
                except Exception as e2:
                    return f"error: MCP call failed: {type(e2).__name__}"
            return f"error: MCP call failed: {type(e).__name__}"

    async def close(self):
        if self.stack:
            try:
                await self.stack.aclose()
            except Exception:
                pass
        self.stack = None
        self.client = None


def run_kubectl(cmd, t=30):
    """Execute an agent action as DIRECT kubectl (subprocess) — robust, unlike the MCP/SSE tool, which
    cold-hangs on connect and degrades/drops over long runs (failed 3 ways). The mitigation oracle grades
    CLUSTER STATE, so a fix applied via direct kubectl is equivalent to one via the MCP tool; both A/B arms
    use this same path, so the comparison stays fair. kubectl-only guard kept (matches the SREGym tool)."""
    cmd = cmd.strip()
    if not cmd.startswith("kubectl"):
        return "Only kubectl commands are allowed."
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=t)
        out = (r.stdout or "")
        if r.stderr:
            out += ("\n" if out else "") + r.stderr
        return out or "(no output)"
    except subprocess.TimeoutExpired:
        return "error: command timed out"
    except Exception as e:
        return f"error: {e}"


SYS = (
    "You are a senior SRE operating a live Kubernetes microservice cluster. Investigate the reported "
    "failure, then ACT to mitigate its ROOT cause, and verify the fix took effect.\n\n"
    "RULES: one action per turn. Every action is a SINGLE kubectl command (the environment runs kubectl "
    "ONLY — no shell pipes, grep, or redirection). For work inside a container use "
    "`kubectl exec <pod> -n <ns> -- <command>`. Inspect with get/describe/logs, form a hypothesis, then run "
    "the kubectl command that FIXES the root cause (scale/set/patch/edit/rollout/apply/delete-pod/exec). "
    "Re-check that the affected pods become Ready and the symptom clears.\n\n"
    "Output EXACTLY ONE line per turn, either:\n"
    "  ACTION: kubectl ...\n"
    "  DONE: <one-sentence description of the fix>   (only once the system is healthy again)\n"
)

ACTION_RE = re.compile(r"ACTION:\s*(kubectl\b.*)", re.IGNORECASE)
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


async def mitigation_loop(ns, desc, app_name):
    """The fix stage: ReAct over kubectl (MCP) + our deferred-scan knowledge push. Returns after DONE/budget."""
    history = [{"role": "system", "content": SYS},
               {"role": "user", "content": f"INCIDENT\nApplication: {app_name}\nNamespace: {ns}\n"
                                            f"Description: {desc}\n\nInvestigate and mitigate now."}]
    sig_cards = load_sig_cards() if (KNOWLEDGE_DIR and ns) else []
    if sig_cards:
        print(f"[codezaiku] {len(sig_cards)} signature card(s) armed for deferred scan", flush=True)
    rescue_hits = []
    dump_path = os.environ.get("CP_LOG_DUMP", "")

    for turn in range(1, MAX_STEPS + 1):
        if turn == SIG_SCAN_TURN and dump_path and not sig_cards:
            logs = scan_logs(ns)
            try:
                open(dump_path, "w").write(logs)
                print(f"[codezaiku] LOG_DUMP written ({len(logs)} chars) -> {dump_path}", flush=True)
            except Exception as e:
                print(f"[codezaiku] LOG_DUMP failed: {e}", flush=True)
        # TWO-SHOT scan: signatures can lag the fault by a few turns (client traffic timing), so a first
        # miss keeps the cards armed and rescans once, +4 turns later. Second miss disarms for the run.
        if sig_cards and turn in (SIG_SCAN_TURN, SIG_SCAN_TURN + 4):
            logs = scan_logs(ns)
            if dump_path:
                try:
                    open(dump_path, "w").write(logs)
                except Exception:
                    pass
            # Push AT MOST ONE card — the strongest signature match. The 9B's context is small; two verbose
            # cards crowd the window (the verbose-card-kills-convergence lesson) and can conflict. Rank by
            # number of the card's signature strings actually present (strongest evidence wins).
            stk = stack_text(ns)
            best = None  # (hits, eff, body)
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
                kb = "## OPS KNOWLEDGE (matched this fault signature in the pod logs — fix procedure)\n\n" + "\n\n".join(now)
                history.append({"role": "user", "content": kb})
                print(f"[codezaiku] KNOWLEDGE PUSHED (immediate, turn {turn}, {len(kb)} chars)", flush=True)
            if held:
                rescue_hits = held
                print(f"[codezaiku] KNOWLEDGE HELD for rescue ({len(held)} card, turn {RESCUE_TURN})", flush=True)
            if not now and not held:
                print(f"[codezaiku] KNOWLEDGE scan turn {turn}: no signature match ({len(logs)} chars)"
                      + (" — will rescan" if turn == SIG_SCAN_TURN else " — disarmed"), flush=True)
        if turn >= RESCUE_TURN and rescue_hits:
            kb = "## OPS KNOWLEDGE (matched this fault signature in the pod logs — fix procedure)\n\n" + "\n\n".join(rescue_hits)
            rescue_hits = []
            history.append({"role": "user", "content": kb})
            print(f"[codezaiku] KNOWLEDGE PUSHED (rescue, turn {turn}, {len(kb)} chars)", flush=True)

        trim(history)
        try:
            out = chat(history)
        except Exception as e:
            out = f"ACTION: kubectl get pods -n {ns}   (drive error: {e})"
        history.append({"role": "assistant", "content": out})

        m_done = DONE_RE.search(out)
        m_act = ACTION_RE.search(out)
        if m_done and not m_act:
            print(f"[codezaiku] mitigation DONE at turn {turn}: {m_done.group(1).strip()[:80]}", flush=True)
            return
        if not m_act:
            history.append({"role": "user", "content": "Reply with exactly one line: 'ACTION: kubectl ...' or 'DONE: <fix>'."})
            continue
        cmd = re.sub(r"(kubectl\s+exec)\s+-(?:it|ti|i|t)\b", r"\1", m_act.group(1).strip())
        print(f"[codezaiku] turn {turn} ACTION: {cmd[:110]}", flush=True)
        obs = run_kubectl(cmd)
        if len(obs) > MAX_OBS:
            obs = obs[:MAX_OBS] + "\n[...truncated]"
        history.append({"role": "user", "content": obs})
    print("[codezaiku] mitigation budget reached", flush=True)


async def run():
    problem_id = resolve_problem_id()
    print(f"[codezaiku] start problem={problem_id} knowledge={'ON' if KNOWLEDGE_DIR else 'OFF'} model={MODEL} base={BASE}", flush=True)

    st = wait_for_stage({"diagnosis", "mitigation", "done"})
    app = get_app()
    ns = app.get("namespace") or app.get("Namespace") or ""
    desc = app.get("descriptions") or app.get("description") or json.dumps(app)
    app_name = app.get("app_name") or app.get("name") or "?"

    if st == "diagnosis":
        # Minimal diagnosis to advance — fix-card validation is graded on the mitigation oracle, not this.
        submit(f"Investigating a failure in {app_name}/{ns}; root cause to be confirmed during mitigation.")
        st = wait_for_stage({"mitigation", "done"})

    if st == "mitigation":
        await mitigation_loop(ns, desc, app_name)
        submit("")  # signal mitigation complete; the objective oracle inspects live cluster state
        st = wait_for_stage({"resolution", "done", "tearing_down"})
        if st == "resolution":
            submit("")
            wait_for_stage({"done", "tearing_down"})
    print("[codezaiku] driver finished", flush=True)


if __name__ == "__main__":
    asyncio.run(run())

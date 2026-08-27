"""cp-refstack P1 — the whole-stack OpsLoop LOCALIZER (the new capability).

On an incident, the harness SENSES the whole stack (per-service docker health + the app's per-dependency
/health report + recent logs) and GROUNDS the model with the dependency topology; the MODEL reasons the
single ROOT-CAUSE service. We do NOT hard-code the RCA — the deterministic baseline (deepest red node whose
deps are green) is only a yardstick to measure the model against.

Measures localization accuracy over a fault battery: for each injected fault we know the true root, and we
record whether the model and the baseline each named it. Hard-down faults (stop a container) are named by
the app /health; degraded faults (qdrant disk-full) leave /health green (reads pass) so only the LOGS name
the culprit — that's where model reasoning beats the health-only baseline.

Usage: python3 localize.py [30b|9b] [battery]
"""
import json, os, subprocess, sys, time
import requests

BASE = {"30b": "http://localhost:8201", "9b": "http://localhost:8200"}[sys.argv[1] if len(sys.argv) > 1 else "30b"]
APP = "http://localhost:28080"

# dependency topology (from the compose depends_on) — the map we ground the model with, not an RCA algorithm
TOPO = {
    "nginx": ["app"],
    "app": ["postgres", "redis", "rabbitmq", "qdrant", "opensearch", "neo4j", "localstack", "ollama"],
}
ENGINES = TOPO["app"]
CNAME = {s: f"refstack-{s}-1" for s in ENGINES + ["app", "nginx"]}


def sh(cmd, t=20):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=t).stdout
    except Exception:
        return ""


# ---------- sense ----------
def compose_health():
    out = sh("cd "${CP_WORK:-/opt/codezaiku}"/refstack && docker compose ps --format '{{.Service}} {{.Status}}'")
    h = {}
    for line in out.splitlines():
        p = line.split(None, 1)
        if len(p) == 2:
            s = p[1].lower()
            h[p[0]] = "healthy" if "healthy" in s and "unhealthy" not in s else \
                      "unhealthy" if ("unhealthy" in s or "restarting" in s or "exited" in s) else \
                      "down" if "exited" in s else "up"
    return h


def app_health():
    try:
        return requests.get(f"{APP}/health", timeout=8).json()
    except Exception as e:
        return {"status": f"unreachable:{type(e).__name__}", "deps": {}}


def logs(service, n=15):
    return sh(f"docker logs --tail {n} {CNAME.get(service, service)} 2>&1")[-1200:]


def sense():
    ph = compose_health()
    ah = app_health()
    # red = anything docker-unhealthy, named down by the app, or the app itself unreachable/degraded
    red = set(s for s, v in ph.items() if v in ("unhealthy", "down"))
    red |= set(d for d, v in ah.get("deps", {}).items() if v != "ok")
    if red & {"s3", "sqs"}:               # the app names s3/sqs but the container is localstack
        red.add("localstack")
    # ALWAYS bundle every engine's recent logs (not just health-red ones): a DEGRADED engine (e.g. qdrant
    # disk-full) is not health-red, so its "No space left" log would be missing if we only showed red ones.
    # The app's log (incl. worker tracebacks that name the failing dependency) gets a longer tail.
    logbundle = {"app": logs("app", 30)}
    for s in ENGINES:
        logbundle[s] = logs(s, 10)
    return ph, ah, logbundle, red


# ---------- deterministic baseline (yardstick only) ----------
def det_root(ph, ah, red):
    # deepest red node whose own deps are all green; ignore the app/nginx when an engine is red (cascade)
    red_engines = [e for e in ENGINES if e in red]
    if red_engines:
        return red_engines[0]           # single-fault battery -> the one red engine is the root
    if ah.get("status") != "ok":
        # app degraded but no engine flagged red (degraded-engine case) -> baseline can't see it
        return "app"
    return "none"


# ---------- model localizer ----------
SYS = ("You are an SRE localizing an incident in a multi-service stack. You are given the dependency "
       "topology, each service's health, the app's own per-dependency health report, and recent logs of "
       "every service. Identify the SINGLE root-cause service.\n"
       "GUIDANCE: `app` and `nginx` are the application/edge tiers — they fail as a SYMPTOM when a backing "
       "engine fails; they are almost never the root. If any engine the app depends on is failing, the ROOT "
       "is that ENGINE, not app/nginx. If the app's /health names a dependency down/degraded, that "
       "dependency is the root. A service can be the root even while its container looks healthy — read the "
       "LOGS (e.g. 'No space left on device', errors) to find an engine that is failing operations though "
       "its health check passes. Pick the deepest failing engine whose own dependencies are healthy.\n"
       "Reply with EXACTLY one line: ROOT: <service-name>.")


def model_root(ph, ah, logbundle):
    ev = ("TOPOLOGY (X depends on Y): " + json.dumps(TOPO) +
          "\n\nDOCKER HEALTH: " + json.dumps(ph) +
          "\n\nAPP /health (per-dependency): " + json.dumps(ah) +
          "\n\nRECENT LOGS:\n" + "\n".join(f"--- {s} ---\n{l}" for s, l in logbundle.items()))
    try:
        r = requests.post(f"{BASE}/v1/chat/completions",
                          json={"model": "default", "temperature": 0, "max_tokens": 1500, "chat_template_kwargs": {"enable_thinking": False},
                                "messages": [{"role": "system", "content": SYS},
                                             {"role": "user", "content": ev}]}, timeout=120)
        txt = r.json()["choices"][0]["message"]["content"]
    except Exception as e:
        return f"error:{e}", ""
    low = txt.lower()
    for line in txt.splitlines():
        if "root:" in line.lower():
            cand = line.lower().split("root:")[1].strip().strip("`.*\"' ").split()
            if cand and cand[0] in (ENGINES + ["app", "nginx"]):
                return cand[0], txt
    # fallback: the last known service name mentioned in the answer (small models skip the strict format)
    names = ENGINES + ["app", "nginx"]
    ment = [(low.rfind(n), n) for n in names if n in low]
    if ment:
        return max(ment)[1], txt
    return "unparsed", txt


# ---------- fault battery (inject / heal), true_root ----------
def stop(s):
    sh(f"docker stop {CNAME[s]}"); return s
def start(s):
    sh(f"docker start {CNAME[s]}"); time.sleep(6)
def os_writeblock():                        # DEGRADED: opensearch serves reads but REJECTS writes (index
    sh("curl -s -m8 -XPUT 'http://localhost:29200/docs/_settings' -H content-type:application/json "
       "-d '{\"index.blocks.write\":true}'"); return "opensearch"  # write-block) — the write-probe catches it
def os_unblock():
    sh("curl -s -m8 -XPUT 'http://localhost:29200/docs/_settings' -H content-type:application/json "
       "-d '{\"index.blocks.write\":null}'"); time.sleep(3)


def battery():
    b = []
    for e in ENGINES:                       # hard-down
        b.append((f"stop-{e}", e, (lambda e=e: stop(e)), (lambda e=e: start(e))))
    # degraded: engine serves READS but rejects WRITES — detectable only by the readiness (write) probe
    b.append(("opensearch-writeblock", "opensearch", os_writeblock, os_unblock))
    return b


def wait_healthy(timeout=140):
    start, restarted = time.time(), False
    while time.time() - start < timeout:
        ah = app_health()
        if ah.get("status") == "ok":
            return True
        # docker embedded-DNS flakes under container stop/start churn -> the app can't resolve a service name
        # ("Temporary failure in name resolution"). A one-time app restart refreshes its resolver; reload
        # nginx too (it caches the app upstream IP across the app restart).
        if not restarted and time.time() - start > 25 and "resolution" in json.dumps(ah).lower():
            sh("docker restart refstack-app-1"); time.sleep(8)
            sh("docker exec refstack-nginx-1 nginx -s reload 2>/dev/null")
            restarted = True
        time.sleep(4)
    return False


def main():
    tier = sys.argv[1] if len(sys.argv) > 1 else "30b"
    print(f"[localize] tier={tier} base={BASE}", flush=True)
    wait_healthy()                          # start from a fully-green stack
    rows = []
    for name, true_root, inject, heal in battery():
        # trigger a real request so degraded faults surface in app logs, then inject and let it settle
        try: requests.post(f"{APP}/ingest", json={"id": "loc", "text": "probe"}, timeout=8)
        except Exception: pass
        inject(); time.sleep(10)
        # exercise the app so the fault appears in logs/health
        for path, body in [("/ingest", {"id": "loc2", "text": "probe2"}), ("/query", {"q": "probe2"})]:
            try: requests.post(f"{APP}{path}", json=body, timeout=15)
            except Exception: pass
        ph, ah, lb, red = sense()
        dr = det_root(ph, ah, red)
        mr, _ = model_root(ph, ah, lb)
        heal()
        wait_healthy()                      # ensure a fully-green stack before the next fault (no contamination)
        row = {"fault": name, "true": true_root, "model": mr, "det": dr,
               "model_ok": mr == true_root, "det_ok": dr == true_root}
        rows.append(row)
        print(f"[localize] {name:20s} true={true_root:11s} model={mr:12s} det={dr:11s} "
              f"MODEL={'OK' if row['model_ok'] else 'x'} DET={'OK' if row['det_ok'] else 'x'}", flush=True)
    m = sum(r["model_ok"] for r in rows); d = sum(r["det_ok"] for r in rows); n = len(rows)
    print(f"[localize] RESULT tier={tier} model={m}/{n} det={d}/{n}", flush=True)
    csv = os.environ.get("LOC_CSV", "")
    if csv:
        open(csv, "w").write("\n".join(json.dumps(r) for r in rows))


if __name__ == "__main__":
    main()

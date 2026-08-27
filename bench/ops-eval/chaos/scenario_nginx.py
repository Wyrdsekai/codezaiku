"""nginx upstream / config-change scenario. The proxied upstream is misconfigured (right host, WRONG port),
so nginx returns 502 for every request — a real config-change incident (a fat-fingered upstream). The
config passes nginx's reload validation (the host resolves) but connections to the dead port are refused, so
it fails at REQUEST time, not reload time. Oracle = the request through nginx returns 200 AND the backend's
real body (whoami's `Hostname:` line) — the body check is the anti-shortcut: a static `return 200` stub
would pass the code but not carry the real backend response. The core injects `sh`."""
import os, subprocess, time
import requests

CONTAINER = os.environ.get("CP_NGINX_CONTAINER", "cp-nginx")
BACKEND = os.environ.get("CP_NGINX_BACKEND", "cp-whoami")
URL = os.environ.get("CP_NGINX_URL", "http://localhost:18080/")
CONF_HOST = os.environ.get("CP_NGINX_CONF", ""${CP_WORK:-/opt/codezaiku}"/chaos/nginx/default.conf")

GOOD = """server {
    listen 80;
    location / {
        proxy_pass http://cp-whoami:80;
    }
}
"""
BAD = GOOD.replace("cp-whoami:80", "cp-whoami:9")   # right host, dead port -> 502 at request time

SYS = (
    "You are a senior SRE operating a host that runs an nginx reverse-proxy container. Investigate the "
    "reported failure, ACT to fix its ROOT cause, and verify the fix took effect.\n\n"
    f"ENVIRONMENT: the container is `{CONTAINER}`; it proxies to a backend service. nginx is reachable at "
    f"{URL}. Run shell on the host: `docker exec {CONTAINER} <cmd>` for in-container work "
    "(nginx -T, nginx -t, cat/sed the config under /etc/nginx/conf.d/, nginx -s reload, cat the logs), and "
    "`curl` to test through nginx.\n\n"
    "RULES: one action per turn, a SINGLE shell command. Inspect, form a hypothesis, then run the command "
    "that FIXES the root cause. Re-check that a request through nginx returns the backend's real response.\n\n"
    "Output EXACTLY ONE line per turn:\n  ACTION: <one shell command>\n  DONE: <one-sentence fix>\n"
)
INCIDENT = ("INCIDENT\nApplication: web edge (nginx reverse proxy)\n"
            "Description: every request through nginx returns 502 Bad Gateway — users cannot reach the app. "
            "Investigate and fix the root cause now.")


def _get():
    try:
        r = requests.get(URL, timeout=6)
        return r.status_code, r.text
    except Exception as e:
        return 0, f"exc:{e}"


def healthy(timeout=60):
    start = time.time()
    while time.time() - start < timeout:
        code, _ = _get()
        if code != 0:                 # nginx answering at all (even 502) means the process is up
            return True
        time.sleep(3)
    return False


def steady_ok():
    code, body = _get()
    ok = code == 200 and "Hostname" in body
    return ok, f"http={code} realbackend={'Hostname' in body}"


def grade():
    return steady_ok()               # the real-backend body check is itself the anti-shortcut


def stack_text():
    return f"nginx reverse proxy docker {CONTAINER}".lower()


def scan_logs():
    text = sh(f"docker logs --tail 120 {CONTAINER} 2>&1").lower()
    state = sh(f"docker exec {CONTAINER} nginx -T 2>&1")
    return text + "\n" + state.lower()


def reset(wait=60):
    print("[chaos] resetting nginx fault (bad upstream port)...", flush=True)
    subprocess.run(f"docker start {BACKEND}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    open(CONF_HOST, "w").write(BAD)          # inject the misconfig into the mounted config
    subprocess.run(f"docker restart {CONTAINER}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if not healthy():
        print("[chaos] WARNING nginx not answering after restart", flush=True)
        return False
    start = time.time()
    while time.time() - start < wait:
        ok, detail = steady_ok()
        if not ok:
            print(f"[chaos] fault applied ({detail})", flush=True)
            return True
        time.sleep(3)
    print(f"[chaos] WARNING fault did not apply within {wait}s", flush=True)
    return False


sh = None  # injected by chaos_core

"""Elasticsearch flood-stage disk-watermark scenario (see README). Real data fills the node past a
watermark set just below occupancy -> genuine read_only_allow_delete -> writes fail. Oracle = the engine's
own API (write 2xx + health + `orders` canary) AND disk genuinely freed (defeats clear-block / raise-watermark
/ rm-rf-all shortcuts). The core injects `sh`."""
import os, subprocess, sys, time
import requests

CONTAINER = os.environ.get("CP_ES_CONTAINER", "cp-es")
ES = os.environ.get("CP_ES_URL", "http://localhost:19200").rstrip("/")
FILL = os.environ.get("CP_FILL_SCRIPT", ""${CP_WORK:-/opt/codezaiku}"/chaos/es_fill.py")
MOUNT = os.environ.get("CP_ES_DATA", "/usr/share/elasticsearch/data")

SYS = (
    "You are a senior SRE operating a host that runs an Elasticsearch container. Investigate the reported "
    "failure, ACT to fix its ROOT cause, and verify the fix took effect.\n\n"
    f"ENVIRONMENT: the container is `{CONTAINER}`. Elasticsearch's REST API is at {ES} (no auth). Run shell "
    f"on the host: `curl` for the ES API, `docker exec {CONTAINER} <cmd>` for in-container work (df, du, rm).\n\n"
    "RULES: one action per turn, a SINGLE shell command. Inspect, form a hypothesis, then run the command "
    "that FIXES the root cause. Re-check that writes succeed and health is green/yellow.\n\n"
    "Output EXACTLY ONE line per turn:\n  ACTION: <one shell command>\n  DONE: <one-sentence fix>\n"
)
INCIDENT = ("INCIDENT\nApplication: search cluster (Elasticsearch)\n"
            "Description: indexing/write requests to Elasticsearch are failing. The cluster serves live "
            "application data (the `orders` index) that must NOT be lost — do not wipe the node or delete all "
            "data. Investigate and fix the root cause now.")

_fault_disk = 100


def _disk_pct():
    try:
        out = subprocess.check_output(["docker", "exec", CONTAINER, "df", "-P", MOUNT], timeout=15)
        return int(out.decode().splitlines()[1].split()[4].rstrip("%"))
    except Exception:
        return 100


def healthy(timeout=90):
    start = time.time()
    while time.time() - start < timeout:
        try:
            if requests.get(f"{ES}/_cluster/health", timeout=5).status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(5)
    return False


def steady_ok():
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
        canary = bool(d.get("found")) and d.get("_source", {}).get("total") == 42
    except Exception as e:
        return False, f"canary-exc:{e}"
    return (write_ok and h != "red" and canary), f"write={r.status_code} health={h} canary={canary}"


def grade():
    func_ok, detail = steady_ok()
    after = _disk_pct()
    freed = after <= _fault_disk - 8
    return (func_ok and freed), f"{detail} disk {_fault_disk}%->{after}% freed={freed}"


def stack_text():
    return f"elasticsearch elastic docker {CONTAINER}".lower()


def scan_logs():
    text = sh(f"docker logs --tail 120 {CONTAINER} 2>&1").lower()
    state = sh(f"docker exec {CONTAINER} df -h {MOUNT} 2>&1")
    for path in ("/cp-probe/_settings?flat_settings", "/_cat/allocation?v"):
        try:
            state += "\n" + requests.get(ES + path, timeout=8).text
        except Exception:
            pass
    return text + "\n" + state.lower()


def reset(target_df=35, wait=180):
    global _fault_disk
    print("[chaos] restarting ES container for a clean node...", flush=True)
    subprocess.run(f"docker restart {CONTAINER}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if not healthy():
        print("[chaos] WARNING ES not healthy after restart", flush=True)
        return False
    print(f"[chaos] resetting fault (fill to df {target_df}%)...", flush=True)
    try:
        subprocess.run([sys.executable, FILL], env=dict(os.environ, TARGET_DF=str(target_df)),
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as e:
        print(f"[chaos] fill failed rc={e.returncode}", flush=True)
        return False
    start = time.time()
    while time.time() - start < wait:
        ok, detail = steady_ok()
        if not ok:
            _fault_disk = _disk_pct()
            print(f"[chaos] fault applied ({detail}) fault_disk={_fault_disk}%", flush=True)
            return True
        time.sleep(6)
    print(f"[chaos] WARNING fault did not apply within {wait}s", flush=True)
    return False


sh = None  # injected by chaos_core

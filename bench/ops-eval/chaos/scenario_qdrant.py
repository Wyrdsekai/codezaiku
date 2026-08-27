"""Qdrant (vector DB) out-of-disk scenario. The storage volume genuinely fills, so Qdrant's WAL/segment
writes fail — upserts return HTTP 500 "No space left on device" while reads/searches still work and the
collection still reports green. Real fault (a large file fills /qdrant/storage), objective oracle (an upsert
succeeds AND a search still returns the canary point), grade also requires the disk genuinely freed
(< 90% used) so the fix is free-disk, not delete-the-collection (which the canary check also catches). The
core injects `sh`."""
import os, subprocess, time
import requests

CONTAINER = os.environ.get("CP_QDRANT_CONTAINER", "cp-qdrant")
Q = os.environ.get("CP_QDRANT_URL", "http://localhost:6333").rstrip("/")
STORAGE = "/qdrant/storage"
COLL = "products"
CANARY = [0.1, 0.2, 0.3, 0.4]

SYS = (
    "You are a senior SRE operating a host that runs a Qdrant vector-database container. Investigate the "
    "reported failure, ACT to fix its ROOT cause, and verify the fix took effect.\n\n"
    f"ENVIRONMENT: the container is `{CONTAINER}`. Qdrant's REST API is at {Q} (no auth); its data/WAL live "
    f"under {STORAGE}. Run shell on the host: `docker exec {CONTAINER} <cmd>` for in-container work "
    "(df, du, ls, rm), and `curl` for the Qdrant API (/collections, /collections/<c>, points, search).\n\n"
    "RULES: one action per turn, a SINGLE shell command. Inspect, form a hypothesis, then run the command "
    "that FIXES the root cause. Re-check that an upsert succeeds and search still returns the data.\n\n"
    "Output EXACTLY ONE line per turn:\n  ACTION: <one shell command>\n  DONE: <one-sentence fix>\n"
)
INCIDENT = ("INCIDENT\nApplication: vector search (Qdrant)\n"
            f"Description: writes to the `{COLL}` collection are failing with HTTP 500 — the app can no "
            "longer index new vectors. The existing indexed vectors must NOT be lost — do not delete or "
            "recreate the collection. Investigate and fix the root cause now.")

_fault_used = 0


def _used_pct():
    try:
        out = subprocess.check_output(["docker", "exec", CONTAINER, "df", "-P", STORAGE], timeout=15)
        return int(out.decode().splitlines()[1].split()[4].rstrip("%"))
    except Exception:
        return 100


def _upsert(pid):
    try:
        r = requests.put(f"{Q}/collections/{COLL}/points", params={"wait": "true"},
                         json={"points": [{"id": pid, "vector": [0.5, 0.5, 0.5, 0.5]}]}, timeout=8)
        return r.status_code == 200
    except Exception:
        return False


def _canary_ok():
    try:
        r = requests.post(f"{Q}/collections/{COLL}/points/search",
                          json={"vector": CANARY, "limit": 1}, timeout=8)
        return r.status_code == 200 and any(p.get("id") == 42 for p in r.json().get("result", []))
    except Exception:
        return False


def healthy(timeout=60):
    start = time.time()
    while time.time() - start < timeout:
        try:
            if requests.get(f"{Q}/healthz", timeout=4).status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def steady_ok():
    up = _upsert(int(time.time()) % 100000 + 1000)
    can = _canary_ok()
    return (up and can), f"upsert={up} canary={can}"


def grade():
    func_ok, detail = steady_ok()
    used = _used_pct()
    freed = used < 90
    return (func_ok and freed), f"{detail} used={used}% freed={freed}"


def stack_text():
    return f"qdrant vector database vector search docker {CONTAINER}".lower()


def scan_logs():
    text = sh(f"docker logs --tail 120 {CONTAINER} 2>&1").lower()
    state = sh(f"docker exec {CONTAINER} df -h {STORAGE} 2>&1")
    # a live upsert attempt surfaces the "No space left on device" error into the haystack
    try:
        state += "\n" + requests.put(f"{Q}/collections/{COLL}/points", params={"wait": "true"},
                                     json={"points": [{"id": 1, "vector": [0.0, 0.0, 0.0, 0.0]}]}, timeout=6).text
    except Exception:
        pass
    return text + "\n" + state.lower()


def reset(wait=90):
    global _fault_used
    print("[chaos] restarting Qdrant container for a clean node...", flush=True)
    subprocess.run(f"docker restart {CONTAINER}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if not healthy():
        print("[chaos] WARNING Qdrant not healthy after restart", flush=True)
        return False
    # fresh collection + protected canary (tmpfs storage wiped on restart)
    requests.put(f"{Q}/collections/{COLL}", json={"vectors": {"size": 4, "distance": "Cosine"}}, timeout=10)
    requests.put(f"{Q}/collections/{COLL}/points", params={"wait": "true"},
                 json={"points": [{"id": 42, "vector": CANARY, "payload": {"name": "canary"}}]}, timeout=10)
    # fill the storage volume so WAL/segment writes fail
    subprocess.run(f"docker exec {CONTAINER} sh -c 'fallocate -l 500M {STORAGE}/hog.bin "
                   f"|| dd if=/dev/zero of={STORAGE}/hog.bin bs=1M count=500'",
                   shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    print(f"[chaos] injected disk-fill (used ~{_used_pct()}%)", flush=True)
    start = time.time()
    while time.time() - start < wait:
        ok, detail = steady_ok()
        if not ok:
            _fault_used = _used_pct()
            print(f"[chaos] fault applied ({detail}) fault_used={_fault_used}%", flush=True)
            return True
        time.sleep(4)
    print(f"[chaos] WARNING fault did not apply within {wait}s", flush=True)
    return False


sh = None  # injected by chaos_core

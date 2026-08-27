#!/usr/bin/env python3
"""Put cp-es into a GENUINE, STABLE flood-stage read-only condition.

Design (robust): index real, incompressible Lucene data to a comfortable occupancy on a 2 GB mount, plus
the `orders` canary (protected live data) and an empty `cp-probe` (so the oracle's write hits an EXISTING
index -> fast 429 under flood, not a hang on auto-create). Then set ES's disk watermarks just BELOW the
genuine resting occupancy, so ES trips flood-stage read-only on REAL data.

Stated setup assumption (measurement honesty): the watermark thresholds are lowered to the fill level. This
is a legitimate per-deployment config (ES watermarks are routinely customised; many shops run 80/85/90),
and it does NOT fabricate the fault — the node is genuinely full of REAL Lucene data and the remediation is
genuinely to delete real old indices (the card's path) or grow the volume. It buys ROBUSTNESS: staying well
clear of 100% avoids the tmpfs-full node wedge that a default-95% tightrope on a small mount keeps hitting.

Remediation that PASSES the oracle (df must drop below the high watermark, canary intact): delete an old
`logs-*` index (card path) or grow the volume. A destructive "delete all data + restart" wipes the canary
and FAILS; clearing the block / raising the watermark leaves disk full and FAILS the oracle's disk check.
Idempotent; used as the per-run fault reset (the harness restarts the container first for a pristine node)."""
import base64, json, os, subprocess, time, urllib.request

E = os.environ.get("CP_ES_URL", "http://localhost:19200").rstrip("/")
CONTAINER = os.environ.get("CP_ES_CONTAINER", "cp-es")
MOUNT = os.environ.get("CP_ES_DATA", "/usr/share/elasticsearch/data")
TARGET_DF = int(os.environ.get("TARGET_DF", "35"))   # fill real data until the mount is this % full


def _df():
    out = subprocess.check_output(["docker", "exec", CONTAINER, "df", "-P", MOUNT]).decode().splitlines()[1].split()
    return int(out[4].rstrip("%"))


def req(method, path, data=None, ct="application/json", retries=6):
    """Retry transient failures — right after a container restart ES answers health 200 before it accepts
    index creation (503 / connection resets during recovery)."""
    last = None
    for i in range(retries):
        try:
            r = urllib.request.Request(E + path, data=(data.encode() if data else None),
                                       headers={"Content-Type": ct}, method=method)
            return urllib.request.urlopen(r, timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code in (400, 404):  # already-exists / not-found: not transient
                raise
            last = e
        except Exception as e:
            last = e
        time.sleep(2 + i)
    raise last


def set_wm(flood, high, low):
    body = json.dumps({"transient": {
        "cluster.routing.allocation.disk.watermark.flood_stage": flood,
        "cluster.routing.allocation.disk.watermark.high": high,
        "cluster.routing.allocation.disk.watermark.low": low}})
    try:
        req("PUT", "/_cluster/settings", body)
    except Exception as e:
        print("set_wm err", e, flush=True)


t0 = time.time()
# hold watermarks high during the fill so indexing is never blocked/relocated; clear any leftover block.
set_wm("99%", "98%", "97%")
try:
    req("PUT", "/_all/_settings", json.dumps({"index.blocks.read_only_allow_delete": None}))
except Exception:
    pass
try:
    for ix in req("GET", "/_cat/indices?h=index").decode().split():
        try:
            req("DELETE", "/" + ix)
        except Exception:
            pass
except Exception:
    pass

# Index real data across a few rolling indices until the mount is TARGET_DF% full. Drive off ACTUAL df,
# not a source-byte count: the random payload is stored non-indexed (mapping below), but even so ES
# overhead makes source bytes a poor proxy for disk — checking _df() each bulk prevents any overshoot.
# The `data` field is a non-indexed keyword (index/doc_values false) so it just occupies _source storage
# and doesn't build a giant inverted index (which amplified disk ~4x and blew past 100%).
mapping = {"settings": {"number_of_replicas": 0, "refresh_interval": "-1"},
           "mappings": {"properties": {"data": {"type": "keyword", "index": False, "doc_values": False}}}}
# Roll to a new index roughly every ROLL_PP percentage-points of disk, so there are only a FEW sizable
# indices (deleting ONE frees a meaningful chunk — enough to drop below the watermark, the card's fix).
ROLL_PP = int(os.environ.get("ROLL_PP", "11"))
idx = 0
name = f"logs-2026.07.{idx:03d}"
try:
    req("PUT", "/" + name, json.dumps(mapping))
except Exception:
    pass
idx_start = _df()
d = idx_start
while d < TARGET_DF:
    # Batch several bulks between df reads — `docker exec df` costs ~0.5s and dominated the fill when
    # called every bulk (~350 bulks -> minutes of pure df). A batch of 8 bulks is ~1-2pp, fine-grained
    # enough to hit the roll boundary and target without overshoot.
    for _ in range(8):
        buf = []
        for _ in range(2000):
            buf.append(json.dumps({"index": {"_index": name}}))
            buf.append(json.dumps({"ts": "2026-07-21T00:00:00Z", "level": "INFO",
                                   "data": base64.b64encode(os.urandom(700)).decode()}))
        req("POST", "/_bulk", "\n".join(buf) + "\n", "application/x-ndjson")
    d = _df()
    if d - idx_start >= ROLL_PP and d < TARGET_DF:
        req("POST", "/" + name + "/_flush")
        idx += 1
        name = f"logs-2026.07.{idx:03d}"
        try:
            req("PUT", "/" + name, json.dumps(mapping))
        except Exception:
            pass
        idx_start = d
        print(f"index rolled -> {name} df={d}% t={int(time.time()-t0)}s", flush=True)
req("POST", "/" + name + "/_flush")
idx += 1

# protected canary + pre-created probe index
try:
    req("PUT", "/orders", json.dumps({"settings": {"number_of_replicas": 0}}))
except Exception:
    pass
req("PUT", "/orders/_doc/1?refresh=true", json.dumps({"customer": "acme", "total": 42, "status": "paid"}))
try:
    req("PUT", "/cp-probe", json.dumps({"settings": {"number_of_replicas": 0}}))
except Exception:
    pass
req("POST", "/_flush")

# wait for a STABLE resting df (two consecutive reads within 1pp), then set watermarks just below it so ES
# trips flood-stage read-only on the genuine occupancy. Comfortable occupancy => nowhere near 100%.
prev = -9
for _ in range(20):
    time.sleep(5)
    cur = _df()
    if abs(cur - prev) <= 1:
        break
    prev = cur
r = _df()
low, high, flood = max(r - 6, 10), max(r - 4, 12), max(r - 2, 14)
set_wm(f"{flood}%", f"{high}%", f"{low}%")
print(f"FINAL resting_df={r}% watermark low/high/flood={low}/{high}/{flood} indices={idx} "
      f"elapsed={int(time.time()-t0)}s", flush=True)

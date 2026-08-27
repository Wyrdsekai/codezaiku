"""Solr core config-change scenario (a config-change fault class, distinct from the resource-exhaustion ones).
A bad directive (a requestHandler referencing a non-existent class) is injected into the core's solrconfig;
on Solr restart the core fails to INITIALIZE, so every query to it returns HTTP 500 "not available due to
init failure" naming the bad class. Real fault (Solr genuinely can't load the config), objective oracle (a
query returns 200 with the canary doc), fix = revert the bad directive + reload the core WITHOUT losing the
indexed data (the anti-shortcut: delete+recreate the core would lose the canary). The core injects `sh`."""
import os, subprocess, time
import requests

CONTAINER = os.environ.get("CP_SOLR_CONTAINER", "cp-solr")
SOLR = os.environ.get("CP_SOLR_URL", "http://localhost:18983").rstrip("/")
CORE = "products"
CONF = f"/var/solr/data/{CORE}/conf/solrconfig.xml"
BAD_LINE = '<requestHandler name="/bad" class="solr.NonExistentHandler"/>'

SYS = (
    "You are a senior SRE operating a host that runs an Apache Solr container. Investigate the reported "
    "failure, ACT to fix its ROOT cause, and verify the fix took effect.\n\n"
    f"ENVIRONMENT: the container is `{CONTAINER}`. Solr's HTTP API is at {SOLR} (no auth); the core is "
    f"`{CORE}` and its config lives under /var/solr/data/{CORE}/conf/. Run shell on the host: "
    f"`docker exec {CONTAINER} <cmd>` for in-container work (cat/grep/sed the conf, restart), and `curl` "
    "for the Solr API (admin/cores STATUS, RELOAD, select).\n\n"
    "RULES: one action per turn, a SINGLE shell command. Inspect, form a hypothesis, then run the command "
    "that FIXES the root cause. Re-check that a query returns the core's documents.\n\n"
    "Output EXACTLY ONE line per turn:\n  ACTION: <one shell command>\n  DONE: <one-sentence fix>\n"
)
INCIDENT = ("INCIDENT\nApplication: search core (Apache Solr)\n"
            f"Description: every query to the `{CORE}` core fails with HTTP 500 — the app's search is down. "
            "The core's indexed documents must NOT be lost — do not delete or recreate the core. Investigate "
            "and fix the root cause now.")


def _query(q="id:CANARY-42"):
    try:
        r = requests.get(f"{SOLR}/solr/{CORE}/select", params={"q": q}, timeout=8)
        return r.status_code, r.text
    except Exception as e:
        return 0, f"exc:{e}"


def healthy(timeout=90):
    start = time.time()
    while time.time() - start < timeout:
        try:
            if requests.get(f"{SOLR}/solr/admin/info/system", timeout=5).status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(4)
    return False


def steady_ok():
    code, body = _query()
    canary = code == 200 and '"numFound":1' in body.replace(" ", "")
    return canary, f"query={code} canary={canary}"


def grade():
    return steady_ok()               # canary-present-on-a-200 is both functional recovery and anti-shortcut


def stack_text():
    return f"solr solrcloud lucene docker {CONTAINER}".lower()


def scan_logs():
    text = sh(f"docker logs --tail 120 {CONTAINER} 2>&1").lower()
    state = sh(f"curl -s -m6 '{SOLR}/solr/admin/cores?action=STATUS'")
    _, q = _query("*:*")
    return text + "\n" + (state + "\n" + q).lower()


def _exec(cmd, t=30):
    subprocess.run(f"docker exec {CONTAINER} {cmd}", shell=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=t)


def reset(wait=90):
    print("[chaos] resetting Solr fault (bad class in solrconfig)...", flush=True)
    subprocess.run(f"docker restart {CONTAINER}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if not healthy():
        print("[chaos] WARNING Solr not healthy after restart", flush=True)
        return False
    # fresh core each run (deterministic): drop + recreate, re-add the protected canary doc
    subprocess.run(f"curl -s -m10 '{SOLR}/solr/admin/cores?action=UNLOAD&core={CORE}&deleteInstanceDir=true'",
                   shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    _exec(f"solr create -c {CORE}")
    subprocess.run(f"curl -s -m10 '{SOLR}/solr/{CORE}/update?commit=true' -H content-type:application/json "
                   f"-d '[{{\"id\":\"CANARY-42\",\"name\":\"widget\"}}]'",
                   shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    # inject the bad directive, then restart so the core fails to INITIALISE
    _exec(f"sed -i 's#</config>#{BAD_LINE}</config>#' {CONF}")
    subprocess.run(f"docker restart {CONTAINER}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if not healthy():
        print("[chaos] WARNING Solr not healthy after fault restart", flush=True)
        return False
    start = time.time()
    while time.time() - start < wait:
        ok, detail = steady_ok()
        if not ok:
            print(f"[chaos] fault applied ({detail})", flush=True)
            return True
        time.sleep(4)
    print(f"[chaos] WARNING fault did not apply within {wait}s", flush=True)
    return False


sh = None  # injected by chaos_core

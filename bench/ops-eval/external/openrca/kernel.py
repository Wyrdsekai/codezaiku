#!/usr/bin/env python3
"""A PERSISTENT python kernel inside the analysis box.

Why this exists: our shell tool spawns a fresh `python3 -c` per turn, so an agent analysing telemetry
re-loads the data EVERY TURN — and OpenRCA's trace file is 1.1 GB per day. It burned its whole turn budget
reloading and never reached an answer (42/51 queries produced nothing).

OpenRCA's own reference agent (the one that scores 11.34% with Claude) runs model-written python in a
persistent IPython kernel (`InteractiveShellEmbed`). This gives our agent the same affordance: load once,
keep the dataframes in memory, query across turns. Parity with their baseline, not extra help.

Protocol (file-based, so it works over a plain one-shot `docker exec`):
  - caller atomically moves code into /tmp/k_in.py
  - kernel execs it in a PERSISTENT globals dict, captures stdout+stderr
  - kernel writes the output to /tmp/k_out.txt then touches /tmp/k_done
"""
import contextlib
import io
import os
import time
import traceback

IN, OUT, DONE = "/tmp/k_in.py", "/tmp/k_out.txt", "/tmp/k_done"
GLOBALS = {}
MAX_OUT = 6000


def _session_state():
    """Render what is ALREADY IN MEMORY, so the model reuses it instead of re-reading the CSVs."""
    loaded = []
    for k, v in list(GLOBALS.items()):
        if k.startswith("_") or k in ("pd", "np", "duckdb"):
            continue
        t = type(v).__name__
        if t == "DataFrame":
            try:
                loaded.append(f"{k}: DataFrame{v.shape}")
            except Exception:
                loaded.append(f"{k}: DataFrame")
        elif t in ("Series", "ndarray"):
            loaded.append(f"{k}: {t}")
        elif t in ("int", "float", "str", "bool", "list", "dict", "set", "tuple", "Timestamp"):
            s = repr(v)
            loaded.append(f"{k}={s[:40]}" + ("…" if len(s) > 40 else ""))
    if not loaded:
        return "[session is empty]"
    return ("[ALREADY LOADED IN THIS SESSION — reuse these directly, they persist to your next call: "
            + "; ".join(loaded[:14]) + "]")

if __name__ == "__main__":
    while True:
        if os.path.exists(IN):
            try:
                code = open(IN).read()
            except OSError:
                time.sleep(0.05)
                continue
            try:
                os.remove(IN)
            except OSError:
                pass
            buf = io.StringIO()
            try:
                with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
                    exec(code, GLOBALS)
            except BaseException:
                buf.write(traceback.format_exc())
            out = buf.getvalue()
            if len(out) > MAX_OUT:
                out = out[:MAX_OUT] + f"\n… [truncated at {MAX_OUT} chars — print less]"
            if not out.strip():
                out = "(no output — print() what you want to see)"
            # SHOW THE LIVE SESSION STATE. Observed failure: the model re-ran `pd.read_csv(...)` on EVERY
            # turn — re-loading a 1.1GB trace each time and exhausting its budget — because it could not SEE
            # that the data was already in memory. A model cannot reuse state it cannot see, so every result
            # now ends with what is already loaded, by name and shape.
            out += "\n" + _session_state()
            with open(OUT, "w") as f:
                f.write(out)
            with open(DONE, "w") as f:
                f.write("1")
        time.sleep(0.05)

#!/usr/bin/env python3
"""HARNESS-SIDE localizer evaluation — no model involved.

Measures RECALL@N: does the ranked shortlist contain the TRUE component? That is exactly the localizer's
job, and it is measurable without spending a single model token — so the detector can be developed against
a real number instead of eyeballed. (OpenSRE calls this the same thing: `required_evidence_sources` proves
the agent consulted the right evidence, independent of the diagnosis text.)

record.csv is read HERE, in the evaluator, and is never handed to any model — this is us grading our own
detector, not leaking the answer key.

Guard against self-deception: `--detector` selects a variant, and each variant is a PRINCIPLED choice, not a
knob tuned until it hits. Two variants only:
  mad-extreme  = the shipped one: max |x - median_base| / (1.4826*MAD_base). BROKEN — a near-constant
                 baseline gives MAD~0, so any wobble explodes (599,956 sigma) and the ranking sorts by
                 baseline flatness rather than anomalousness.
  shift        = |median_window - median_base| / robust_scale_with_floor. Uses the window MEDIAN (a fault is
                 a SUSTAINED shift; the extreme chases single-sample spikes) and floors the scale by the
                 series' own magnitude so a flat baseline cannot divide by ~0.
"""
import argparse, csv, glob, os, re, sys
import numpy as np
import pandas as pd

MONTHS = ["january","february","march","april","may","june","july","august","september","october","november","december"]
COMP = ["cmdb_id","serviceName","service","tc","host","instance"]
KPI  = ["name","kpi_name","metric","kpi"]
TIME = ["timestamp","startTime","time","ts"]
VAL  = ["value","val"]

def parse_window(instr):
    m = re.search(r"([a-z]+)\s+(\d{1,2}),\s*(\d{4}).{0,40}?from\s+(\d{1,2}):(\d{2})\s*(?:to|-|until)\s*(\d{1,2}):(\d{2})",
                  instr.lower(), re.S)
    if not m: return None
    mon = next((i+1 for i,x in enumerate(MONTHS) if x.startswith(m.group(1))), None)
    if not mon: return None
    y, d = int(m.group(3)), int(m.group(2))
    import datetime as dt
    z = dt.timezone(dt.timedelta(hours=8))
    t0 = dt.datetime(y, mon, d, int(m.group(4)), int(m.group(5)), tzinfo=z).timestamp()
    t1 = dt.datetime(y, mon, d, int(m.group(6)), int(m.group(7)), tzinfo=z).timestamp()
    if t1 <= t0: t1 = t0 + 1800
    return f"{y:04d}_{mon:02d}_{d:02d}", t0, t1

def pick(cols, opts):
    return next((o for o in opts if o in cols), None)

_CACHE = {}
def load_day(root, day):
    if day in _CACHE: return _CACHE[day]
    rows = []
    for f in sorted(glob.glob(os.path.join(root, day, "metric", "*.csv"))):
        try: head = pd.read_csv(f, nrows=1)
        except Exception: continue
        c,k,t,v = pick(head.columns,COMP), pick(head.columns,KPI), pick(head.columns,TIME), pick(head.columns,VAL)
        if not all([c,k,t,v]): continue
        try: df = pd.read_csv(f, usecols=[c,k,t,v])
        except Exception: continue
        df[t] = pd.to_numeric(df[t], errors="coerce"); df[v] = pd.to_numeric(df[v], errors="coerce")
        df = df.dropna(subset=[t,v])
        if df.empty: continue
        if df[t].max() > 1e11: df[t] = df[t]/1000.0
        rows.append(df.rename(columns={c:"comp",k:"kpi",t:"ts",v:"val"})[["comp","kpi","ts","val"]])
    d = pd.concat(rows, ignore_index=True) if rows else pd.DataFrame(columns=["comp","kpi","ts","val"])
    _CACHE[day] = d
    return d

def rank(d, t0, t1, detector):
    win  = d[(d.ts>=t0)&(d.ts<=t1)]
    base = d[(d.ts<t0)|(d.ts>t1)]
    if win.empty or base.empty: return []
    bg = base.groupby(["comp","kpi"])["val"]
    bmed = bg.median(); bmad = bg.apply(lambda x: np.median(np.abs(x-np.median(x))))
    out = []
    for (comp,kpi), g in win.groupby(["comp","kpi"]):
        if (comp,kpi) not in bmed.index: continue
        mb, db = bmed[(comp,kpi)], bmad[(comp,kpi)]
        if detector == "mad-extreme":
            scale = 1.4826*db
            if scale <= 0:
                dev = g.val[g.val != mb]
                if dev.empty: continue
                out.append((99.0, comp, kpi)); continue
            z = float(((g.val-mb).abs()/scale).max())
            if z < 3.0: continue
            out.append((z, comp, kpi))
        else:  # shift
            mw = float(g.val.median())
            dw = float(np.median(np.abs(g.val - mw)))
            # floor the scale by the series' OWN magnitude so a flat baseline can't divide by ~0
            scale = max(1.4826*max(db, dw), 0.01*max(abs(mb), abs(mw)), 1e-9)
            z = abs(mw - mb)/scale
            if z < 3.0: continue
            out.append((min(z, 1e4), comp, kpi))
    out.sort(key=lambda r: -r[0])
    return out

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=""${CP_WORK:-/opt/codezaiku}"/openrca-data/Telecom")
    ap.add_argument("--detector", default="shift", choices=["mad-extreme","shift"])
    ap.add_argument("--topn", type=int, default=25)
    a = ap.parse_args()

    q = list(csv.DictReader(open(os.path.join(a.data, "query.csv"))))
    gt = pd.read_csv(os.path.join(a.data, "record.csv"))
    root = os.path.join(a.data, "telemetry")

    hits = tot = 0; ranks = []; lens = []
    for i, row in enumerate(q):
        w = parse_window(row["instruction"])
        if not w: continue
        day, t0, t1 = w
        d = load_day(root, day)
        if d.empty: continue
        # the truth for THIS window: records whose timestamp falls inside it
        truth = gt[(gt.timestamp>=t0)&(gt.timestamp<=t1)]
        if truth.empty: continue
        tot += 1
        r = rank(d, t0, t1, a.detector)
        comps = []
        for _, _, _ in []: pass
        seen = []
        for z, c, k in r:
            if c not in seen: seen.append(c)
        want = set(truth.component.astype(str))
        pos = next((idx+1 for idx, c in enumerate(seen[:a.topn]) if c in want), None)
        if pos: hits += 1; ranks.append(pos)
        lens.append(min(len(seen), a.topn))
        print(f"  q{i:<3} {day} want={sorted(want)!s:<22} -> "
              f"{'HIT @'+str(pos) if pos else 'MISS'}   top5={seen[:5]}")
    print()
    NCOMP = 55   # distinct components carrying metrics in Telecom
    avg_len = float(np.mean(lens)) if lens else 0.0
    rec = 100*hits/max(1,tot)
    rand = 100*min(avg_len, NCOMP)/NCOMP          # random shortlist of the SAME length
    print(f"  detector={a.detector}  n={tot}")
    print(f"  RECALL@{a.topn}         : {hits}/{tot} = {rec:.1f}%")
    print(f"  avg shortlist len : {avg_len:.1f} of {NCOMP} components")
    print(f"  RANDOM at that len: {rand:.1f}%")
    print(f"  LIFT over random  : {rec/max(rand,1e-9):.2f}x   <-- the only number that means anything")
    if ranks: print(f"  median rank when hit: {int(np.median(ranks))}")

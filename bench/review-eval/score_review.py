#!/usr/bin/env python3
"""Score `codezaiku review` against the mined human-review corpus.

The unit is a PR, not a comment: one PR carries several human findings, so precision
and recall are only meaningful per-change.

MATCHING IS LOCATION-ONLY, DELIBERATELY. A finding counts as matched when it names the
same file and lands within +/-WINDOW lines of a human comment. That over-counts — a
finding at the right line for the wrong reason scores as a hit — and it is chosen
anyway, because the alternative is an LLM judge, and a mis-calibrated grader has
produced a confident wrong number on this project before. The inflation is measured by
hand-reading a sample instead of being assumed away. Treat the output as an UPPER BOUND
on true agreement, and only ever compare two harness versions with it.
"""
import json, subprocess, sys, os, collections, tempfile, re, functools, random

# Progress goes to stderr and is read while the run is in flight. Block-buffered to a
# file it appears only at exit, which is indistinguishable from a hang — flush every line.
print = functools.partial(print, flush=True)  # noqa: A001

WINDOW = int(os.environ.get("SCORE_WINDOW", "5"))
DRIVE = os.environ.get("CODEZAIKU_DRIVE", "http://localhost:8200")
CP = os.path.expanduser("~/src/codezaiku/bin/codezaiku")

FINDING_RE = re.compile(r"^\[(high|med|low)\]\s+(\S+?):(\d+)(?:-(\d+))?\s+—", re.I)

def pr_files(repo, num):
    """Changed files with their POST-change content, so the review has code to read.

    Without this the harness handed the model a patch and an EMPTY directory: every read_file
    found nothing and the model correctly reported itself blocked ("I need to first read the
    original files to find the actual defects"). That produced 41% zero-finding runs and a recall
    number measured on a crippled configuration — the harness starving the thing it was scoring.
    Real usage reviews a checkout, so the scorer must give it one.
    """
    pr = subprocess.run(["gh", "api", f"/repos/{repo}/pulls/{num}"],
                        capture_output=True, text=True)
    if pr.returncode != 0:
        return {}
    head = json.loads(pr.stdout)["head"]["sha"]
    listing = subprocess.run(["gh", "api", f"/repos/{repo}/pulls/{num}/files?per_page=100"],
                             capture_output=True, text=True)
    if listing.returncode != 0:
        return {}
    out, skipped = {}, []
    for f in json.loads(listing.stdout):
        if f.get("status") == "removed":
            continue
        path = f["filename"]
        blob = subprocess.run(
            ["gh", "api", f"/repos/{repo}/contents/{path}?ref={head}",
             "-H", "Accept: application/vnd.github.raw"], capture_output=True, text=True)
        # NO SILENT SKIPS. A file that fails to fetch leaves the model unable to read code the diff
        # references, and it then reports itself blocked — which is indistinguishable from "found
        # nothing" unless the skip is announced. One PR in the last run was still starved for exactly
        # this reason while the totals showed only a zero.
        if blob.returncode != 0:
            skipped.append(f"{path} (fetch failed)")
        elif len(blob.stdout) >= MAX_FILE_BYTES:
            skipped.append(f"{path} ({len(blob.stdout)//1000}k, over the {MAX_FILE_BYTES//1000}k cap)")
        else:
            out[path] = blob.stdout
    if skipped:
        print(f"  {repo}#{num}: {len(skipped)} file(s) NOT materialised — the review cannot read them: "
              f"{', '.join(skipped[:3])}{' …' if len(skipped) > 3 else ''}", file=sys.stderr)
    return out


def pr_diff(repo, num):
    r = subprocess.run(["gh", "api", f"/repos/{repo}/pulls/{num}",
                        "-H", "Accept: application/vnd.github.v3.diff"],
                       capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None

RAW_DIR = os.environ.get("SCORE_RAW_DIR")   # set to keep every run's full output

# Cap on a single materialised file. The first value here was 400k and it was chosen for no reason:
# writing a large file to disk costs nothing, and read_file paginates, so the model reads the part it
# needs rather than getting NOTHING. At 400k it starved a 639k Kafka test file and a vscode file that
# missed by 1% (404k) — both in repos added specifically for language diversity, so the cap was biting
# hardest exactly where the sample was meant to broaden. 8M is past any source file that is not a
# vendored blob, and those are not worth reviewing anyway.
MAX_FILE_BYTES = int(os.environ.get("SCORE_MAX_FILE_BYTES", 8_000_000))


def run_review(diff_text, max_turns=25, tag=None, files=None):
    with tempfile.TemporaryDirectory() as td:
        patch = os.path.join(td, "change.diff")
        with open(patch, "w") as f:
            f.write(diff_text)
        proj = os.path.join(td, "proj"); os.makedirs(proj)
        for path, content in (files or {}).items():
            dest = os.path.join(proj, path)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "w") as fh:
                fh.write(content)
        r = subprocess.run([CP, "review", proj, "@" + patch, DRIVE, str(max_turns)],
                           capture_output=True, text=True, timeout=1200)
        out = r.stdout
        # KEEP THE RAW OUTPUT. Scoring is separable from running: without this, a run that
        # produced nothing leaves no record of WHY, and the only way to ask is to re-run the
        # model — which is exactly what saving raw output exists to avoid.
        if RAW_DIR and tag:
            os.makedirs(RAW_DIR, exist_ok=True)
            with open(os.path.join(RAW_DIR, f"{tag}.log"), "w") as fh:
                fh.write(out + "\n===== STDERR =====\n" + r.stderr)
    got, seen = [], False
    for line in out.splitlines():
        if line.startswith("=== REVIEW ==="):
            seen = True; continue
        if not seen:
            continue
        if line.startswith("COULD NOT LOCATE"):
            break                      # unanchored findings have no location to score
        m = FINDING_RE.match(line.strip())
        if m:
            start = int(m.group(3))
            end = int(m.group(4)) if m.group(4) else start
            got.append({"sev": m.group(1).lower(), "file": m.group(2),
                        "line": start, "end": end})
    return got, out

def score(pr_rows, ours):
    truth = [{"file": r["path"], "line": r["line"]} for r in pr_rows if r.get("line")]
    matched_t, matched_o = set(), set()
    for ti, t in enumerate(truth):
        for oi, o in enumerate(ours):
            if oi in matched_o:
                continue
            if o["file"].endswith(t["file"]) or t["file"].endswith(o["file"]):
                # Findings can span a RANGE (`file:8-9`). Match if the human line falls
                # anywhere in [start-WINDOW, end+WINDOW]; using the start line alone silently
                # misses a comment sitting at the other end of the range.
                if o["line"] - WINDOW <= t["line"] <= o.get("end", o["line"]) + WINDOW:
                    matched_t.add(ti); matched_o.add(oi); break
    return len(truth), len(ours), len(matched_t), len(matched_o)

if __name__ == "__main__":
    corpus = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 10
    # K repeats per PR. One pass per PR is NOISE, demonstrated: two PRs re-run with identical
    # code and model flipped 0->5 and 3->0 findings. A single number off K=1 cannot support a
    # claim, so K is explicit and the spread is reported alongside the mean.
    K = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    rows = [json.loads(l) for l in open(corpus)]
    rows = [r for r in rows if not r["is_bot"] and r.get("line")]
    by_pr = collections.defaultdict(list)
    for r in rows:
        by_pr[(r["repo"], r["pr"])].append(r)
    # STRATIFIED across repos, then shuffled. Sorting by fewest-comments and taking the head looked
    # like "smallest first" but ties broke by corpus order, so all 25 PRs came from the first two
    # repos — kafka had 480 scorable threads and rust 208, and neither was ever scored. A number off
    # that sample describes two Python projects, not the corpus.
    per_repo = collections.defaultdict(list)
    for key, rows_ in by_pr.items():
        per_repo[key[0]].append((key, rows_))
    rng = random.Random(int(os.environ.get("SCORE_SEED", "17")))
    for v in per_repo.values():
        rng.shuffle(v)
    prs, i = [], 0
    while len(prs) < limit and any(len(v) > i for v in per_repo.values()):
        for repo_name in sorted(per_repo):          # sorted: repo order must not depend on dict order
            if len(prs) >= limit:
                break
            if len(per_repo[repo_name]) > i:
                prs.append(per_repo[repo_name][i])
        i += 1

    T = O = MT = MO = UT = 0
    truncated = 0
    unstable = 0
    scored = 0
    per_pr_recall = []
    for (repo, num), pr_rows in prs:
        d = pr_diff(repo, num)
        if not d:
            print(f"  {repo}#{num}: diff unavailable — SKIPPED", file=sys.stderr); continue
        if len(d) > 200_000:
            print(f"  {repo}#{num}: diff {len(d)//1000}k — SKIPPED (too big for the window)", file=sys.stderr); continue
        # NO SILENT CAPS: review inlines only DIFF_BUDGET_CHARS (12k) of a diff and reads the rest
        # by tool. A human comment sitting in the truncated tail depresses recall for a reason that
        # has nothing to do with review quality, so count it and say so rather than let it hide.
        if len(d) > 12_000:
            truncated += 1
        files = pr_files(repo, num)
        runs = []
        for k in range(K):
            try:
                ours, raw = run_review(d, tag=f"{repo.replace('/','_')}#{num}.run{k+1}",
                                       files=files)
            except subprocess.TimeoutExpired:
                print(f"  {repo}#{num} run{k+1}: TIMED OUT", file=sys.stderr); continue
            runs.append(score(pr_rows, ours) + (ours,))
        if not runs:
            print(f"  {repo}#{num}: every run failed — SKIPPED", file=sys.stderr); continue
        t = runs[0][0]
        matched_per_run = [r[2] for r in runs]
        found_per_run = [r[1] for r in runs]
        # UNION: did ANY run locate the human comment? Separating "can it" from "does it
        # reliably" is the whole reason for repeating — a capability that fires 1 run in 3 is a
        # different problem from one that never fires.
        union = max(matched_per_run)
        T += t; O += sum(found_per_run) / len(runs); MT += sum(matched_per_run) / len(runs)
        UT += union
        scored += 1
        if t: per_pr_recall.append((sum(matched_per_run) / len(runs)) / t)
        print(f"  {repo}#{num}: human {t}, ours {found_per_run}, matched {matched_per_run}"
              f"{' UNSTABLE' if len(set(matched_per_run)) > 1 else ''}", file=sys.stderr)
        print(json.dumps({"repo": repo, "pr": num, "human": t,
                          "ours_per_run": found_per_run, "matched_per_run": matched_per_run,
                          "union_matched": union,
                          "our_findings_run1": runs[0][4],
                          "human_lines": [(r['path'], r['line']) for r in pr_rows]}))
    print(f"\nTOTALS over {scored} PRs SCORED (of {len(prs)} selected) x K={K}: "
          f"human {T}, ours/run {O:.1f}, matched/run {MT:.1f}", file=sys.stderr)
    if per_pr_recall:
        import statistics as _st
        print(f"  recall, PER-PR mean    : {100*_st.mean(per_pr_recall):.1f}%   <- PRIMARY. Equal weight "
              f"per PR.", file=sys.stderr)
        print(f"     Comment-weighting lets one heavily-reviewed PR dominate: a 33-comment PR outweighs "
              f"33 single-comment ones,", file=sys.stderr)
        print(f"     so finding 2 of 33 on a big review scores worse than missing the only comment on a "
              f"small one.", file=sys.stderr)
    if T:
        # Wilson interval on the aggregate. CLAUDE.md's "K=3 is noise" is about per-class
        # comparisons; for a single pooled proportion the honest answer is an interval, not a
        # rule of thumb — and it makes clear when two runs are not actually distinguishable.
        import math
        n_trials = T * K
        p_hat = (MT * K) / n_trials if n_trials else 0.0
        z = 1.96
        denom = 1 + z*z/n_trials
        centre = (p_hat + z*z/(2*n_trials)) / denom
        half = z*math.sqrt(p_hat*(1-p_hat)/n_trials + z*z/(4*n_trials*n_trials)) / denom
        print(f"  recall, comment-weighted: {100*MT/T:.1f}%   95% CI "
              f"[{100*(centre-half):.1f}%, {100*(centre+half):.1f}%]  (n={n_trials} PR-runs)",
              file=sys.stderr)
        print(f"  recall, UNION of runs  : {100*UT/T:.1f}%   <- found by at least one run",
              file=sys.stderr)
        print(f"  the gap between them IS the instability.", file=sys.stderr)
    print(f"  NOT reporting 'precision': it measures overlap with where humans chose to comment,",
          file=sys.stderr)
    print(f"  not correctness — a finding no human made is not thereby wrong.", file=sys.stderr)
    print(f"  NOTE: location-only match, +/-{WINDOW} lines — an UPPER BOUND, not agreement.", file=sys.stderr)
    if truncated:
        print(f"  NOTE: {truncated}/{len(prs)} PRs had diffs over the 12k inline budget and were only "
              f"PARTLY shown to the model — recall is understated by an unknown amount.", file=sys.stderr)

#!/usr/bin/env python3
"""Harvest (diff hunk, human review comment) pairs from merged PRs — v2.

v1 gated on "a later commit touched this file", which I pitched as the fix for the
published dataset's actionability problem. Hand-reading 33 pairs showed it is mildly
ANTI-correlated: the comments it dropped were MORE likely to be real findings (87.5%)
than the ones it kept (72.0%), because in an active PR files churn for unrelated
reasons. That signal is now metadata, never a gate.

GraphQL replaces it and costs less:
  * thread-opening comment only  — replies are discussion, excluded structurally
  * isOutdated                   — the code the comment points at has since CHANGED.
                                   Line-level by construction, and free.
  * isResolved                   — a human marked the thread addressed.
  * author.__typename            — Bot vs User. 12% of the v1 pilot was an automated
                                   scanner; valid findings, but not human ground truth.
"""
import json, subprocess, sys

Q = """
query($owner:String!, $name:String!, $cursor:String) {
  repository(owner:$owner, name:$name) {
    pullRequests(states:MERGED, first:25, orderBy:{field:UPDATED_AT, direction:DESC}, after:$cursor) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number
        reviewThreads(first:50) {
          nodes {
            isResolved
            isOutdated
            comments(first:1) {
              nodes { body path line originalLine diffHunk url
                      author { login __typename } }
            }
          }
        }
      }
    }
  }
}"""

BOT_LOGINS = {"codecov", "dependabot", "renovate", "sonarcloud", "zizmor",
              "github-actions", "coderabbitai", "sourcery-ai", "pre-commit-ci"}

def is_bot(author):
    if not author:
        return True
    if author.get("__typename") == "Bot":
        return True
    login = (author.get("login") or "").lower()
    return login.endswith("[bot]") or any(b in login for b in BOT_LOGINS)

def run(owner, name, pages):
    cursor, out, errs = None, [], []
    for _ in range(pages):
        cmd = ["gh", "api", "graphql", "-f", f"query={Q}",
               "-F", f"owner={owner}", "-F", f"name={name}"]
        if cursor:
            cmd += ["-F", f"cursor={cursor}"]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            errs.append(r.stderr.strip()[:160]); break
        d = json.loads(r.stdout)
        if "errors" in d:
            errs.append(json.dumps(d["errors"])[:160]); break
        prs = d["data"]["repository"]["pullRequests"]
        for pr in prs["nodes"]:
            for th in pr["reviewThreads"]["nodes"]:
                cs = th["comments"]["nodes"]
                if not cs:
                    continue
                c = cs[0]
                body = (c.get("body") or "").strip()
                if len(body) < 25 or not c.get("diffHunk"):
                    continue
                out.append({
                    "repo": f"{owner}/{name}", "pr": pr["number"],
                    "path": c.get("path"), "line": c.get("line") or c.get("originalLine"),
                    "diff_hunk": c["diffHunk"], "comment": body,
                    "author": (c.get("author") or {}).get("login"),
                    "is_bot": is_bot(c.get("author")),
                    "resolved": th["isResolved"], "outdated": th["isOutdated"],
                    "has_suggestion": "```suggestion" in body,
                    "url": c.get("url"),
                })
        if not prs["pageInfo"]["hasNextPage"]:
            break
        cursor = prs["pageInfo"]["endCursor"]
    return out, errs

if __name__ == "__main__":
    pages = int(sys.argv[2]) if len(sys.argv) > 2 else 2
    rows, all_errs = [], []
    for repo in sys.argv[1].split(","):
        o, n = repo.split("/")
        got, errs = run(o, n, pages)
        human = [g for g in got if not g["is_bot"]]
        print(f"  {repo}: {len(got)} threads, {len(human)} human "
              f"({sum(1 for g in human if g['has_suggestion'])} with suggestion)", file=sys.stderr)
        rows += got; all_errs += errs
    for r in rows:
        print(json.dumps(r))
    if all_errs:
        print(f"  !! {len(all_errs)} FAILED call(s) — a silent failure reads as 'no data':", file=sys.stderr)
        for e in all_errs[:3]:
            print(f"     {e}", file=sys.stderr)

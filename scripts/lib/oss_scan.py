#!/usr/bin/env python3
"""Privacy / secret scanner for the public open-source export.

Dependency-free on purpose: it must run on a fresh box with nothing but python3.
gitleaks/trufflehog are better at credential entropy — run them too if you have
them — but the highest-risk leak for THIS repo is not a stray AWS key. It is the
operator's infrastructure: machine names, absolute home paths, the layout of the
boxes the benchmarks ran on. No off-the-shelf scanner knows those.

It scans the EXPORT, not the source. Scanning the source would tell you what you
excluded; scanning the result tells you what you are about to publish.

Exit code 0 = clean, 1 = HIGH findings. Usage:

    python3 scripts/lib/oss_scan.py <root-dir> [--json]
"""
from __future__ import annotations

import json
import os
import re
import sys

def _default_names_file() -> str:
    """~/.codezaiku, or the pre-rename ~/.codeplane if that is the one that exists.

    Mirrors Config.home() on the Java side. Without this the scanner looks in a directory that a
    machine predating the rename does not have, finds no configured names, and every rule goes
    INERT -- which the export correctly refuses to proceed on, but only because it checks. A
    scanner that reports clean because it did not look is worse than no scanner.
    """
    home = os.path.expanduser("~")
    now = os.path.join(home, ".codezaiku", "oss-private-names")
    was = os.path.join(home, ".codeplane", "oss-private-names")
    return was if (not os.path.exists(now) and os.path.exists(was)) else now
# ── the names to hunt for, loaded from OUTSIDE the repo ────────────────────────
#
# A scanner that hardcodes the hostnames it hunts for publishes them itself (this one
# flagged its own rule table on the first run). So the names live in the environment, or
# in a file deliberately kept out of the repo and therefore impossible to export:
#
#     ~/.codezaiku/oss-private-names        KEY=value lines, also shell-sourceable
#
# The trap this now guards against is worse than a missing rule. An UNSET variable used to
# be interpolated as the literal "__no_hosts_configured__", which compiled fine and matched
# nothing — so the export printed "0 HIGH" while the two rules that hunt machine names were
# inert. Silence from an unconfigured scanner is not evidence of cleanliness. Every unset
# key is recorded in UNCONFIGURED and main() refuses to report clean while any remain.
PRIVATE_NAMES_FILE = os.path.expanduser(
    os.environ.get("CODEZAIKU_OSS_NAMES_FILE", _default_names_file()))

UNCONFIGURED: list[str] = []


def _private_names() -> dict:
    vals: dict[str, str] = {}
    try:
        with open(PRIVATE_NAMES_FILE, "r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, _, v = line.partition("=")
                    vals[k.strip()] = v.strip().strip('"').strip("'")
    except OSError:
        pass
    for k, v in os.environ.items():         # the environment wins over the file
        if k.startswith(("CODEZAIKU_OSS_", "CODEPLANE_OSS_")) and v:
            vals[k] = v
    # The project was CodePlane and this file lives OUTSIDE the repo, so a rename in here cannot
    # reach it. Accept the old key spelling rather than go quietly inert on a machine that was
    # configured before the rename -- the failure mode of an unconfigured scanner is a clean
    # report, which is the one result that must never be wrong.
    for k in list(vals):
        if k.startswith("CODEPLANE_OSS_"):
            vals.setdefault("CODEZAIKU_OSS_" + k[len("CODEPLANE_OSS_"):], vals[k])
    return vals


_NAMES = _private_names()


def alt(key: str) -> str:
    """Alternation over the configured names, or a pattern that cannot match — loudly."""
    parts = [re.escape(p) for p in re.split(r"[|,\s]+", _NAMES.get(key, "").strip()) if p]
    if not parts:
        UNCONFIGURED.append(key)
        return r"(?!x)x"
    return "|".join(parts)


# HIGH   = almost certainly a real leak; blocks the export.
# REVIEW = often legitimate (a doc quoting an example); read it yourself.
RULES: list[tuple[str, str, re.Pattern, str]] = [
    # The leading slash is OPTIONAL: the comment that leaked a real username described a
    # path-handling bug and so wrote it WITHOUT one ("home/<user>/..."), slipping straight
    # past the anchored version of this rule. Placeholder users are excluded so that
    # generic examples and /home/ubuntu in a container doc stay quiet.
    ("home-path", "HIGH",
     re.compile(r"\bhome/(?!(?:user|username|your-?user|me|someone|foo|bar|ubuntu|root)\b)"
                r"[a-z][a-z0-9_-]{2,}/"),
     "path into someone's home directory — leaks the username and box layout"),
    ("operator-host", "HIGH", re.compile(r"\b(" + alt("CODEZAIKU_OSS_HOSTS") + r")\b"),
     "name of a machine or data volume in the operator's setup"),
    # Case-SENSITIVE on purpose: a lowercase machine name is the box, capitalised it
    # is usually a proper noun in prose. Kept REVIEW for that reason.
    ("dev-box", "REVIEW",
     re.compile(r"(?<![A-Za-z])(?:" + alt("CODEZAIKU_OSS_DEVBOX") + r")(?![A-Za-z])"),
     "possible reference to the operator's dev box"),
    # Handles and sibling-project names are PII and unreleased-project disclosure
    # respectively. Both leaked through prose and code comments, which no credential
    # scanner looks at.
    # Boundary EXCLUDES both '_' and '-' deliberately (\w would include '_'): a joined token
    # like INTEGRATION_<NAME>.md is still a disclosure, and hid behind (?<![\w-]).
    #
    # '-' was in this class until a bundled gate fixture named <project>-bakery shipped for months
    # and adding the project to the names file enforced NOTHING — the trailing '-' satisfied the
    # lookahead, so the compound never matched while the bare name would have. A separator does not
    # make a name less disclosed, and the underscore case was already decided the same way.
    ("operator-handle", "HIGH",
     re.compile(r"(?i)(?<![A-Za-z0-9])(?:" + alt("CODEZAIKU_OSS_HANDLES") + r")(?![A-Za-z0-9])"),
     "the operator's personal handle or name"),
    ("sibling-project", "HIGH",
     re.compile(r"(?i)(?<![A-Za-z0-9])(?:" + alt("CODEZAIKU_OSS_PROJECTS") + r")(?![A-Za-z0-9])"),
     "name of a private sibling project that is not public yet"),
    # A weights filename is how a private model name reaches shipping code: it was the
    # DEFAULT value of the request's `model` field, so it was both a leak and a live bug
    # for anyone pointing CodeZaiku at Ollama or a hosted API.
    # Standard/illustrative filenames are skipped so the rule stays readable: adapter_model
    # and ggml-model are fixed conventions, and model-q4_K_M / f16 are quantisation examples.
    ("model-weight-file", "REVIEW",
     # The left guard is (?<![\w.\-]) rather than \b: \b also matches INSIDE a hyphenated
     # name, so "model-q8_0.gguf" would re-match at "q8_0" and defeat the exclusion.
     re.compile(r"(?<![\w.\-])(?!(?:adapter_model|ggml-model|model|f16|f32|your-?model)[.\-])"
                r"[\w.-]{3,}\.(?:gguf|safetensors)\b"),
     "a specific model filename — check it is illustrative, not one machine's build"),
    ("private-ipv4", "REVIEW",
     re.compile(r"\b(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b"),
     "RFC1918 address — fine as a generic example, a leak if it is a real host"),
    ("private-key-block", "HIGH",
     re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"),
     "private key material"),
    ("aws-key", "HIGH", re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AWS access key id"),
    ("bearer-token", "HIGH",
     re.compile(r"\b(?:sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{30,}|xox[baprs]-[A-Za-z0-9-]{10,})"),
     "API token (OpenAI / GitHub / Slack shape)"),
    ("hf-token", "HIGH", re.compile(r"\bhf_[A-Za-z0-9]{30,}\b"),
     "HuggingFace access token"),
    ("assigned-credential", "HIGH",
     re.compile(r"(?i)\b(?:password|passwd|secret|api[_-]?key)\b\s*[=:]\s*['\"][^'\"\s]{12,}['\"]"),
     "hard-coded credential"),
    ("ssh-target", "REVIEW", re.compile(r"\bssh\s+[a-z][\w.-]*@[\w.-]+"),
     # Written with angle brackets so the rule does not match its own description: a scanner that
     # reports itself puts a permanent entry in REVIEW, and a section that always has entries is a
     # section people stop reading.
     "ssh <user>@<host> — check it is illustrative, not real"),
]

SKIP_DIRS = {".git", "build", ".gradle", "node_modules", "__pycache__", ".venv"}
SKIP_EXT = {".png", ".jpg", ".jpeg", ".gif", ".pdf", ".zip", ".gz", ".tar", ".jar",
            ".class", ".gguf", ".onnx", ".safetensors", ".bin", ".ico", ".woff", ".woff2"}
# Test fixtures deliberately contain credential-shaped strings; a benchmark that
# injects a weak password is demonstrating the fault it detects.
ALLOW = [
    re.compile(r"badpass|changeme|example\.com|your-?(token|key|password)|<[A-Za-z_]+>"),
    # A line that ENUMERATES credential header strings is a detection rule, not a
    # credential: knowledge-packs/security-patterns/secrets-in-code.md exists to
    # teach exactly these patterns. Requires the markers to be inline-code quoted
    # AND listed, so a real pasted key (which spans lines and is not backticked)
    # still trips the rule.
    re.compile(r"`-----BEGIN [^`]*-----`.*`-----BEGIN"),
]


# A file may declare that its credential-shaped strings are deliberate fixtures, by
# carrying this marker. It is OPT-IN and per-file on purpose: whitelisting "anything
# under test/" would hide a real key someone pasted into a test, which is a normal way
# for secrets to escape. Writing the marker is a human act that can be reviewed in the
# diff, and it suppresses only the CREDENTIAL rules — the operator-specific rules
# (hostnames, handles, home paths) still apply, because a fixture file is no more
# entitled to leak the box's identity than any other file.
FIXTURE_MARKER = "oss-scan: synthetic-credentials"
CREDENTIAL_RULES = {"private-key-block", "aws-key", "bearer-token", "hf-token",
                    "assigned-credential"}



# THIS project's own published identity. CodeZaiku is released at github.com/Wyrdsekai/codezaiku, so
# the owner segment appears in the clone url, the attestation command, the .deb Homepage field and
# the issue-chooser links. The owner happens to share a name with a sibling project, which made the
# sibling-project rule block the export on 12 occurrences of our OWN repo url.
#
# Deliberately narrow, and case-SENSITIVE: only the exact string `Wyrdsekai/codezaiku` is exempt, and
# only for the sibling-project rule. A bare mention of the owner name on its own, a mis-cased variant
# of it (which would fail attestation anyway), and the owner paired with any OTHER repo all still
# trip HIGH — including in this very comment, which is why it does not spell those forms out. This is
# not an allowlist entry — those suppress a whole line for every rule, which is how a real leak got
# swallowed once. It removes one exact published token from one rule's input.
OWN_REPO_IDENTITY = re.compile(r"(?:https://)?(?:github\.com/)?Wyrdsekai/codezaiku\b")

def scan_file(path: str, rel: str, findings: list):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except OSError:
        return
    fixtures_declared = FIXTURE_MARKER in text
    for lineno, line in enumerate(text.splitlines(), 1):
        # An ALLOW hit suppresses only the CREDENTIAL rules, for the same reason FIXTURE_MARKER does:
        # these patterns exist to excuse credential-SHAPED false positives (`<TOKEN>`, `changeme`,
        # `example.com`), and a line containing a placeholder is no more entitled to leak the box's
        # identity than any other line. Measured: the placeholder pattern `<[A-Za-z_]+>` matched an
        # unrelated `<exe>` in a code comment and silently swallowed a REAL sibling-project HIGH on the
        # same line — the whole line, for every rule. An allowlist that disables the leak detector is
        # worse than no allowlist, because it reports clean.
        allowed = any(a.search(line) for a in ALLOW)
        for rid, sev, pat, why in RULES:
            if (fixtures_declared or allowed) and rid in CREDENTIAL_RULES:
                continue
            # See OWN_REPO_IDENTITY: our own repo url is not a disclosure of the sibling.
            subject = OWN_REPO_IDENTITY.sub("", line) if rid == "sibling-project" else line
            m = pat.search(subject)
            if not m:
                continue
            findings.append({
                "rule": rid, "severity": sev, "file": rel, "line": lineno,
                "match": m.group(0)[:80], "why": why,
                "text": line.strip()[:160],
            })


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    as_json = "--json" in sys.argv
    findings: list[dict] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if os.path.splitext(fn)[1].lower() in SKIP_EXT:
                continue
            full = os.path.join(dirpath, fn)
            scan_file(full, os.path.relpath(full, root), findings)

    high = [f for f in findings if f["severity"] == "HIGH"]
    review = [f for f in findings if f["severity"] == "REVIEW"]

    if as_json:
        print(json.dumps({"high": high, "review": review}, indent=2))
    else:
        for label, group in (("HIGH", high), ("REVIEW", review)):
            if not group:
                continue
            print(f"\n=== {label} ({len(group)}) ===")
            seen = {}
            for f in group:
                seen.setdefault(f["rule"], []).append(f)
            for rule, items in seen.items():
                print(f"\n  [{rule}] {items[0]['why']}  ({len(items)} occurrence(s))")
                for f in items[:8]:
                    print(f"    {f['file']}:{f['line']}  {f['text']}")
                if len(items) > 8:
                    print(f"    … and {len(items) - 8} more")
        if not findings:
            print("clean — no findings")
        print(f"\n{len(high)} HIGH, {len(review)} REVIEW")
        if UNCONFIGURED:
            print(f"\n!! {len(UNCONFIGURED)} rule(s) INERT — they matched nothing because no names "
                  f"are configured: {', '.join(sorted(set(UNCONFIGURED)))}")
            print(f"!! A clean result here means the scanner did not look, not that the tree is "
                  f"clean.\n!! Set them in {PRIVATE_NAMES_FILE} (KEY=name1,name2) or in the "
                  f"environment.")

    # An inert rule is a scanner failure, not a pass — this is the exact shape of the bug
    # that let a real hostname rule sit dead while the export reported itself clean.
    return 1 if (high or UNCONFIGURED) else 0


if __name__ == "__main__":
    sys.exit(main())

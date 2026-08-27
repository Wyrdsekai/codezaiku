#!/usr/bin/env python3
"""Regression tests for the export scanner.

Run: python3 scripts/lib/test_oss_scan.py

This scanner has failed OPEN twice — reporting "0 HIGH" while missing real leaks — and a leak
detector that reports clean is worse than none, because the export ships on its word. Both failures
are pinned here.

Dependency-free and self-contained (no pytest): it must run anywhere the scanner runs, including a
release box with nothing but python3.

The token below is synthetic — a test that a real token is caught has to contain something
token-shaped. Declared with the scanner's own opt-in marker, which suppresses only the CREDENTIAL
rules for this file; the identity rules (hosts, handles, home paths) still apply here, because a test
file is no more entitled to leak the box's identity than any other.

oss-scan: synthetic-credentials
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCANNER = os.path.join(HERE, "oss_scan.py")

FAILURES: list[str] = []


# EVERY name key must be set, or the scanner rightly refuses to report clean (it cannot
# distinguish "nothing here" from "I did not look"). Omitting DEVBOX made three of these
# tests fail against a scanner that was behaving correctly.
DEFAULT_NAMES = "PROJECTS=acmeproj\nHOSTS=bigbox\nHANDLES=jdoe\nDEVBOX=devbox\n"


def scan(files: dict[str, str], names: str = DEFAULT_NAMES):
    """Scan a throwaway tree with a throwaway names file; return (exit_code, stdout)."""
    with tempfile.TemporaryDirectory() as d:
        tree = os.path.join(d, "tree")
        os.makedirs(tree)
        for name, body in files.items():
            path = os.path.join(tree, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(body)
        namefile = os.path.join(d, "names")
        with open(namefile, "w", encoding="utf-8") as fh:
            for line in names.strip().splitlines():
                k, _, v = line.partition("=")
                fh.write(f"CODEZAIKU_OSS_{k.strip()}={v.strip()}\n")
        env = dict(os.environ)
        env["CODEZAIKU_OSS_NAMES_FILE"] = namefile
        for k in list(env):
            if k.startswith("CODEZAIKU_OSS_") and k != "CODEZAIKU_OSS_NAMES_FILE":
                del env[k]          # the environment wins over the file; keep the test hermetic
        p = subprocess.run([sys.executable, SCANNER, tree],
                           capture_output=True, text=True, env=env)
        return p.returncode, p.stdout


def check(label: str, condition: bool, detail: str = ""):
    print(f"  {'ok  ' if condition else 'FAIL'}  {label}")
    if not condition:
        FAILURES.append(f"{label}{': ' + detail if detail else ''}")


# ── the two failures that shipped ────────────────────────────────────────────────

def test_allow_does_not_suppress_identity_rules():
    """A placeholder on the line must not disable the leak detector for that line.

    `<[A-Za-z_]+>` exists to excuse credential-shaped placeholders like `<TOKEN>`. It matched an
    unrelated `<exe>` in a code comment and, because an ALLOW hit skipped the whole line for EVERY
    rule, silently swallowed a real sibling-project HIGH.
    """
    code, out = scan({"a.java": "// run `<exe> --version` against acmeproj to check\n"})
    check("a placeholder on the line does not hide an identity leak", code == 1 and "acmeproj" in out, out)


def test_underscore_joined_name_is_caught():
    """`\\w` includes '_', so (?<![\\w-]) let INTEGRATION_ACMEPROJ.md through untouched."""
    code, out = scan({"b.java": "// see INTEGRATION_ACMEPROJ.md for details\n"})
    check("an underscore-joined private name is still a disclosure", code == 1, out)


# ── the properties those fixes must not have broken ──────────────────────────────

def test_allow_still_excuses_credential_shapes():
    code, out = scan({"c.py": 'password = "changeme_please_1234"\n'})
    check("a placeholder credential is still excused", code == 0, out)


def test_real_credentials_still_caught():
    code, out = scan({"d.py": 'api_key = "sk-abcdefghijklmnopqrstuvwxyz012345"\n'})
    check("a real-looking token is still HIGH", code == 1, out)


def test_plain_name_and_host_caught():
    code, out = scan({"e.md": "deployed acmeproj on bigbox last week\n"})
    check("plain private project + host names are caught",
          code == 1 and "acmeproj" in out and "bigbox" in out, out)


def test_case_insensitive_for_identity_names():
    code, out = scan({"f.md": "See ACMEPROJ and AcmeProj.\n"})
    check("identity matching is case-insensitive", code == 1, out)


def test_clean_tree_is_clean():
    code, out = scan({"g.md": "A perfectly ordinary file about widgets.\n"})
    check("a clean tree reports clean", code == 0 and "0 HIGH" in out, out)


def test_unconfigured_scanner_refuses_to_report_clean():
    """Silence from a scanner that never looked is not evidence of cleanliness."""
    code, out = scan({"h.md": "anything at all\n"}, names="")
    check("an unconfigured scanner says so loudly", "INERT" in out, out)


def test_word_boundaries_still_hold():
    """The widened boundary must not start matching inside unrelated words."""
    code, out = scan({"i.md": "the acmeprojector is a different product\n"})
    check("a longer word containing the name is not a match", code == 0, out)


if __name__ == "__main__":
    print("oss_scan regression tests")
    for fn in sorted((v for k, v in list(globals().items()) if k.startswith("test_")),
                     key=lambda f: f.__code__.co_firstlineno):
        fn()
    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILED")
        for f in FAILURES:
            print("  -", f)
        sys.exit(1)
    print("all passed")

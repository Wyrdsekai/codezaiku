#!/usr/bin/env python3
"""
Code-as-action A/B — the same six-turn conversations as probe.py, with the JSON tool
schemas replaced by ONE interface: a fenced python code block.

THE HYPOTHESIS (docs/IDEA_CODE_AS_ACTION.md)
    The 0%-tool-call small coders (qwen2.5-coder-3b, phi-4-mini: 100% narrated) are not
    agentically incapable — they are JSON-tool-call incapable. They already write the
    action as code in prose; this probe makes that the sanctioned channel and measures
    whether the ACTED row flips.

COMPARABILITY, BY CONSTRUCTION
    Conversations, canned tool results, K, and grading all come from probe.py itself —
    imported, not copied. Grading synthesizes OpenAI-shaped tool_calls out of the parsed
    code block and feeds them to probe.classify, so an outcome name here means exactly
    what it means in the JSON run. The raw record keeps the response VERBATIM; synthesis
    happens on a copy at grade time (scoring stays separable from running).

USAGE
    probe-code.py probe --endpoint http://host:8212 --label qwen2.5-coder-3b --out raw.jsonl -k 5
    probe-code.py grade raw.jsonl [...]
"""

import argparse
import ast
import copy
import importlib.util
import json
import re
import sys
import time
from pathlib import Path

_spec = importlib.util.spec_from_file_location("probe", Path(__file__).parent / "probe.py")
probe = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(probe)

# One interface, stated positively (the house prompt rule): what to do, never what not to.
SYSTEM_CODE = (
    "You are a coding assistant working in a small Java project. You act by writing one "
    "fenced python code block that calls exactly one of these functions:\n\n"
    "    list_files(path)          # list the files in a project directory\n"
    "    read_file(path)           # read one file\n"
    "    edit_file(path, old, new) # replace the exact text `old` with `new` in a file\n"
    "    run_tests()               # run the project's test suite\n\n"
    "The function's result arrives in the next message. When the user asks something you "
    "can answer from what you already know, answer directly in plain prose."
)

SIGS = {
    "list_files": ["path"],
    "read_file": ["path"],
    "edit_file": ["path", "old", "new"],
    "run_tests": [],
}

FENCE = re.compile(r"```[a-zA-Z]*\n(.*?)```", re.S)
CALL = re.compile(r"\b(list_files|read_file|edit_file|run_tests)\s*\(")


def parse_calls(content):
    """Fenced code -> [(name, args_json_string)]. Empty when no block calls a function.

    Arguments are recovered with ast.literal_eval where possible; a call whose arguments
    cannot be recovered still counts as a call with unusable args, which is exactly what
    probe.classify's tool_badargs outcome exists for."""
    out = []
    for block in FENCE.findall(content or ""):
        for m in CALL.finditer(block):
            name = m.group(1)
            tail = block[m.end():]
            depth, i = 1, 0
            while i < len(tail) and depth:
                if tail[i] == "(":
                    depth += 1
                elif tail[i] == ")":
                    depth -= 1
                i += 1
            raw = tail[: i - 1] if depth == 0 else tail
            try:
                vals = ast.literal_eval("(" + raw + ",)") if raw.strip() else ()
                args = {k: v for k, v in zip(SIGS[name], vals)}
                out.append((name, json.dumps(args)))
            except Exception:
                out.append((name, raw))   # unusable on purpose -> tool_badargs
    return out


def synthesize(rec):
    """A COPY of the record with tool_calls built from the code block, for probe.classify."""
    r = copy.deepcopy(rec)
    resp = r.get("response")
    if not resp:
        return r
    msg = ((resp.get("choices") or [{}])[0].get("message") or {})
    calls = parse_calls(msg.get("content"))
    if calls:
        msg["tool_calls"] = [
            {"id": f"code_{i}", "type": "function",
             "function": {"name": n, "arguments": a}}
            for i, (n, a) in enumerate(calls)
        ]
    return r


def run(args):
    out = open(args.out, "w", encoding="utf-8")
    n_turns = 0
    t0 = time.monotonic()
    for rep in range(args.k):
        for conv in probe.CONVERSATIONS:
            messages = [{"role": "system", "content": SYSTEM_CODE}]
            for idx, (user, expect) in enumerate(conv["turns"]):
                messages.append({"role": "user", "content": user})
                body = {
                    "messages": messages,
                    "temperature": args.temperature,
                    "max_tokens": args.max_tokens,
                    "stream": False,
                }
                resp, err, secs = _post(args.endpoint, body, args.timeout)
                rec = {
                    "label": args.label, "conversation": conv["name"], "rep": rep,
                    "turn": idx + 1, "user": user, "expect": expect,
                    "seconds": round(secs, 2), "error": err, "response": resp,
                    "interface": "code",
                    "prompt_tokens": (resp or {}).get("usage", {}).get("prompt_tokens"),
                    "completion_tokens": (resp or {}).get("usage", {}).get("completion_tokens"),
                    "finish_reason": ((resp or {}).get("choices") or [{}])[0].get("finish_reason"),
                }
                out.write(json.dumps(rec) + "\n")
                out.flush()
                n_turns += 1
                if err or not resp:
                    break
                msg = ((resp.get("choices") or [{}])[0].get("message") or {})
                content = msg.get("content") or ""
                messages.append({"role": "assistant", "content": content})
                for name, _ in parse_calls(content):
                    messages.append({
                        "role": "user",
                        "content": "Result of " + name + ":\n```\n"
                                   + probe.TOOL_RESULTS.get(name, "ok") + "\n```",
                    })
            sys.stderr.write(f"  {args.label}  rep {rep + 1}/{args.k}  {conv['name']}\n")
    out.close()
    sys.stderr.write(f"wrote {n_turns} turns to {args.out} in {(time.monotonic() - t0) / 60:.1f} min\n")


def _post(endpoint, body, timeout):
    import urllib.request, urllib.error
    req = urllib.request.Request(
        endpoint.rstrip("/") + "/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode()), None, time.monotonic() - t0
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode()[:400]
        except Exception:
            detail = ""
        return None, f"HTTP {e.code}: {detail}", time.monotonic() - t0
    except Exception as e:  # noqa: BLE001
        return None, f"{type(e).__name__}: {e}", time.monotonic() - t0


def grade(paths):
    import tempfile
    synthed = []
    for p in paths:
        with open(p, encoding="utf-8") as fh:
            records = [synthesize(json.loads(l)) for l in fh if l.strip()]
        t = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False, encoding="utf-8")
        for r in records:
            t.write(json.dumps(r) + "\n")
        t.close()
        synthed.append(t.name)
    probe.grade(synthed)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("probe")
    p.add_argument("--endpoint", required=True)
    p.add_argument("--label", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("-k", type=int, default=5)
    p.add_argument("--temperature", type=float, default=0.7)
    p.add_argument("--max-tokens", type=int, default=768)
    p.add_argument("--timeout", type=int, default=300)
    p.set_defaults(func=run)
    g = sub.add_parser("grade")
    g.add_argument("files", nargs="+")
    g.set_defaults(func=lambda a: grade(a.files))
    a = ap.parse_args()
    a.func(a)


if __name__ == "__main__":
    main()

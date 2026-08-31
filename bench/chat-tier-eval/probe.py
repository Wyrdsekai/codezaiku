#!/usr/bin/env python3
"""
Multi-turn tool-calling probe — does a model still call tools correctly on turn 6?

WHY THIS EXISTS
    `codezaiku smoke` answers "can this model emit one tool call". That is the wrong
    question for a chat interface. Two measured facts make it the wrong question:

      * multi-turn degradation (arXiv 2505.06120): -39% mean going single-turn to
        multi-turn, of which +112% is *unreliability* rather than lost aptitude.
      * the register failure (../wyrdsekai/RESULTS_MODEL_EVAL.md): in a conversational
        register our 9B NARRATES the action instead of calling the tool — `navigate`
        52.8% -> 24.0%. And it errs the other way too: correctly declining to call
        anything was 90% base vs 55% tuned.

    So a chat-capable model has to do three separable things, and a single-turn smoke
    test measures none of them over a conversation:

        tool-ok    called the right tool, with usable arguments, when it should have
        spurious   called a tool when it should simply have answered
        narrated   described the action in prose instead of calling the tool
        mute       produced neither a call nor usable content

    `narrated` and `mute` are reported separately on purpose. They look identical in a
    pass/fail count and have completely different causes — one is a register problem,
    the other is a broken generation.

DESIGN RULES THIS FOLLOWS (CLAUDE.md)
    * Scoring is separable from running. `probe` writes raw responses to JSONL and
      grades nothing. `grade` reads that file. A grader bug is fixed by re-grading,
      never by re-running a model.
    * Tool RESULTS are canned and identical for every model, so two models see the
      same conversation. A model that gets a different tool result is not comparable.
    * K>=5 per conversation across several conversation shapes. K=1 is noise, and so
      is K=3.
    * Turns alternate between "should act" and "should just answer". A probe made only
      of act-turns cannot see `spurious` at all, and would score a model that calls a
      tool on literally every input as perfect.

USAGE
    probe.py probe  --endpoint http://host:8210 --label qwen3.8-27b --out raw.jsonl [-k 5]
    probe.py grade  raw.jsonl [raw2.jsonl ...]

    stdlib only — no deps, runs anywhere a JDK-less box does.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

# --------------------------------------------------------------------------------------
# The tools. Deliberately small and coding-shaped: this is the tier test for `codezaiku
# chat`, not a general function-calling benchmark. Four tools is also about the number a
# small model can hold in mind at once.
# --------------------------------------------------------------------------------------

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "List the files in a directory of the project.",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string", "description": "Directory, relative to the project root."}},
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the full contents of one file.",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string", "description": "File path, relative to the project root."}},
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "edit_file",
            "description": "Replace an exact string in a file with another.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "old": {"type": "string", "description": "Exact text to replace."},
                    "new": {"type": "string", "description": "Replacement text."},
                },
                "required": ["path", "old", "new"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_tests",
            "description": "Run the project's test suite and return the result.",
            "parameters": {"type": "object", "properties": {}, "required": []},
        },
    },
]

SYSTEM = (
    "You are a coding assistant working in a small Java project. "
    "Use the provided tools to inspect and change files. "
    "When the user asks a question you can answer from what you already know, answer it directly."
)

# Canned tool results — identical for every model and every run, so the conversation is
# the same experiment each time. Keyed by tool name; arguments are not consulted, because
# a model that reads a plausible-but-wrong path should still see a well-formed result and
# be judged on its NEXT turn rather than derailed by an error we invented.
TOOL_RESULTS = {
    "list_files": "Client.java\nClientTest.java\nRetryPolicy.java\npom.xml",
    "read_file": (
        "public final class Client {\n"
        "    private final HttpClient http;\n"
        "    public Response send(Request r) {\n"
        "        return http.execute(r);\n"
        "    }\n"
        "}\n"
    ),
    "edit_file": "edited: 1 replacement made",
    "run_tests": "4 tests, 4 passed, 0 failed",
}

# --------------------------------------------------------------------------------------
# The conversations. Each turn declares what a correct model does:
#     {"tool": "<name>"}            -> must call that tool
#     {"tool": ["a", "b"]}          -> either is correct
#     {"answer": True}              -> must NOT call any tool; must produce prose
#
# A SET, not a single name, because the dry run (2026-08-27, Qwen3.8-27B) showed the
# first expectation was simply wrong: asked to "read Client.java" from a cold start, the
# model listed the directory first. That is not a failure, it is caution about a path it
# has not seen — and scoring it `tool_wrong` would have measured my expectation rather
# than the model. Where two tool choices are both defensible, say so.
#
# CASCADE, stated because it changes how `change` reads: that conversation is dependent —
# turn 4 assumes turn 3 landed. A miss early therefore lowers everything after it, and
# the later turns cannot be attributed cleanly. That is what a real chat does, so it is
# kept, but `change` is reported per-conversation and should never be pooled with the
# others into one headline. `explore` and `chatty` have no such dependency.
# Three shapes, because one shape measures one thing. The `chatty` conversation exists
# specifically to catch `spurious`: every one of its turns is answerable without a tool,
# and a model that reaches for one anyway is telling us it cannot hold a conversation
# without acting on it.
# --------------------------------------------------------------------------------------

CONVERSATIONS = [
    {
        "name": "explore",  # read-only, the `read` rung — the most common standalone ask
        "turns": [
            ("What files are in the src directory?", {"tool": "list_files"}),
            ("Show me Client.java.", {"tool": "read_file"}),
            ("In one sentence, what does that class do?", {"answer": True}),
            ("Does it have any retry handling?", {"answer": True}),
            ("Read RetryPolicy.java too.", {"tool": "read_file"}),
            ("Which of the two files would you change to add a timeout?", {"answer": True}),
        ],
    },
    {
        "name": "change",  # act rung — the transition from talking to doing
        "turns": [
            ("Read Client.java for me.", {"tool": ["read_file", "list_files"]}),
            ("I want to add a retry. What's the smallest change?", {"answer": True}),
            ("Do it — wrap the http.execute call in a retry.", {"tool": "edit_file"}),
            ("Now run the tests.", {"tool": "run_tests"}),
            ("Did that pass?", {"answer": True}),
            ("Good. Anything else you'd change while we're in here?", {"answer": True}),
        ],
    },
    {
        "name": "chatty",  # every turn answerable WITHOUT a tool — this is the spurious detector
        "turns": [
            ("Hi — are you able to edit files in this project?", {"answer": True}),
            ("What's the difference between a checked and unchecked exception in Java?", {"answer": True}),
            ("Would you use one for a network timeout?", {"answer": True}),
            ("Thanks. And what does 'idempotent' mean for a retry?", {"answer": True}),
            ("Makes sense.", {"answer": True}),
            ("OK. Remind me what we've been talking about.", {"answer": True}),
        ],
    },
]

# --------------------------------------------------------------------------------------
# Transport
# --------------------------------------------------------------------------------------


def call(endpoint, model, messages, temperature, max_tokens, timeout, api_key=None):
    """One /v1/chat/completions request. Returns (parsed_json, error_string, seconds)."""
    body = {
        "messages": messages,
        "tools": TOOLS,
        "tool_choice": "auto",
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
    }
    if model:
        body["model"] = model
    req = urllib.request.Request(
        endpoint.rstrip("/") + "/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    if api_key:
        req.add_header("Authorization", api_key if api_key.startswith("Bearer ") else "Bearer " + api_key)
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode()), None, time.monotonic() - t0
    except urllib.error.HTTPError as e:
        # Read the body: a 4xx from a chat-template rejection says exactly what is wrong,
        # and discarding it turns a fixable config error into "the model failed".
        detail = ""
        try:
            detail = e.read().decode()[:400]
        except Exception:
            pass
        return None, f"HTTP {e.code}: {detail}", time.monotonic() - t0
    except Exception as e:  # noqa: BLE001 — the failure mode matters more than the type
        return None, f"{type(e).__name__}: {e}", time.monotonic() - t0


# --------------------------------------------------------------------------------------
# Run
# --------------------------------------------------------------------------------------


def run(args):
    out = open(args.out, "w", encoding="utf-8")
    n_turns = 0
    t_start = time.monotonic()
    for rep in range(args.k):
        for conv in CONVERSATIONS:
            # The conversation ACCUMULATES. That is the point — this probe measures what
            # a growing transcript does to tool calling, so it must not be reset per turn.
            messages = [{"role": "system", "content": SYSTEM}]
            for idx, (user, expect) in enumerate(conv["turns"]):
                messages.append({"role": "user", "content": user})
                resp, err, secs = call(
                    args.endpoint, args.model, messages, args.temperature,
                    args.max_tokens, args.timeout, args.api_key,
                )

                rec = {
                    "label": args.label,
                    "conversation": conv["name"],
                    "rep": rep,
                    "turn": idx + 1,
                    "user": user,
                    "expect": expect,
                    "seconds": round(secs, 2),
                    "error": err,
                    "response": resp,
                    # Prompt size is what the multi-turn hypothesis is actually about, so
                    # record it per turn rather than reconstructing it later.
                    "prompt_tokens": (resp or {}).get("usage", {}).get("prompt_tokens"),
                    "completion_tokens": (resp or {}).get("usage", {}).get("completion_tokens"),
                    "finish_reason": ((resp or {}).get("choices") or [{}])[0].get("finish_reason"),
                }
                out.write(json.dumps(rec) + "\n")
                out.flush()
                n_turns += 1

                if err or not resp:
                    # A transport failure ends this conversation but not the run. Recording
                    # it and moving on keeps a dead endpoint from looking like a bad model.
                    break

                msg = (resp.get("choices") or [{}])[0].get("message") or {}
                # Append verbatim so the next turn sees exactly what the server said.
                messages.append({
                    "role": "assistant",
                    "content": msg.get("content") or "",
                    **({"tool_calls": msg["tool_calls"]} if msg.get("tool_calls") else {}),
                })
                for tc in msg.get("tool_calls") or []:
                    name = (tc.get("function") or {}).get("name", "")
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tc.get("id", "call_0"),
                        "content": TOOL_RESULTS.get(name, "ok"),
                    })

            sys.stderr.write(f"  {args.label}  rep {rep + 1}/{args.k}  {conv['name']}\n")
            sys.stderr.flush()
    out.close()
    elapsed = time.monotonic() - t_start
    sys.stderr.write(f"wrote {n_turns} turns to {args.out} in {elapsed / 60:.1f} min\n")


# --------------------------------------------------------------------------------------
# Grade — reads the raw file, touches no model
# --------------------------------------------------------------------------------------

# A turn that should have called a tool, produced no call, but produced prose, is only
# "narrated" if the prose is actually about doing the thing. A model that asks a
# clarifying question is doing something legitimate and is scored separately, because
# lumping it in with narration would punish the one safe behaviour we want.
CLARIFY_MARKERS = ("?",)

# Read-only vs acting. The distinction matters on ANSWER turns: a model that reads a file
# before answering a question about it is being careful, and calling that the same thing
# as a model that EDITS a file when asked a question would conflate diligence with damage.
READ_ONLY_TOOLS = {"list_files", "read_file"}

# MEASURED, 2026-08-27 (Qwen3.8-27B, K=5, 90 turns): every one of the seven "misses" in
# the first grading was the grader being wrong, not the model. Asked "what's the smallest
# change?", it read the files before advising. Told "do it", it read the test file first —
# read-before-edit, which is a rule OUR OWN EditFileTool enforces. Told "anything else?",
# it went back to re-check something that looked inconsistent.
#
# A strong model can always find a legitimate reason to gather more context, so scoring
# "did it call the tool on exactly this turn" measures compliance with my script rather
# than capability. What we actually want to know is whether it ACTS AT ALL, and whether it
# narrates instead. So an act-turn also passes if the tool lands on the FOLLOWING turn —
# `tool_ok_late`. Reading first and then doing the thing is not a failure.
#
# This was fixed in the grader and re-scored against the SAME raw file. No model was
# re-run to fix a scoring bug.
LOOKAHEAD_TURNS = 1


def tool_names(rec):
    """The tool names called in one record, or [] — including on a failed record."""
    if not rec or not rec.get("response"):
        return []
    msg = ((rec["response"].get("choices") or [{}])[0].get("message") or {})
    return [(c.get("function") or {}).get("name", "") for c in (msg.get("tool_calls") or [])]


def classify(rec, nxt=None):
    """One record -> one outcome string. Pure function of the records; no I/O.

    `nxt` is the following turn in the same conversation+rep, used only to let an
    act-turn pass late (see LOOKAHEAD_TURNS above)."""
    if rec.get("error") or not rec.get("response"):
        return "transport_error"
    choice = (rec["response"].get("choices") or [{}])[0]
    msg = choice.get("message") or {}
    calls = msg.get("tool_calls") or []
    content = (msg.get("content") or "").strip()
    expect = rec["expect"]

    # MEASURED TRAP, 2026-08-27, Qwen3.8-27B: a reasoning model spends its budget on
    # `reasoning_content` first. Ask for 16 tokens and you get empty `content` with
    # finish_reason "length" — which is INDISTINGUISHABLE from a model that produced
    # nothing, and would be scored `mute`. That number would be about our max_tokens,
    # not about the model. Name it instead, so a run with too small a budget is visible
    # as a probe misconfiguration rather than a model failure.
    if choice.get("finish_reason") == "length" and not calls and not content:
        return "truncated"

    if "tool" in expect:
        want = expect["tool"]
        want = [want] if isinstance(want, str) else list(want)
        if calls and (calls[0].get("function") or {}).get("name", "") in want:
            try:
                raw = (calls[0].get("function") or {}).get("arguments") or "{}"
                json.loads(raw) if isinstance(raw, str) else raw
            except Exception:
                return "tool_badargs"
            return "tool_ok"
        # Called something, but not the expected tool. If the expected tool lands on the
        # next turn, this was preparation, not a miss.
        if calls and any(n in want for n in tool_names(nxt)):
            return "tool_ok_late"
        if calls:
            return "tool_wrong"
        if not content:
            return "mute"
        if content.rstrip().endswith(CLARIFY_MARKERS):
            return "clarified"
        return "narrated"

    # expect answer
    if calls:
        names = [(c.get("function") or {}).get("name", "") for c in calls]
        # Reading before answering is diligence. Editing or running something when asked
        # a question is the behaviour worth counting against a model.
        return "read_first" if all(n in READ_ONLY_TOOLS for n in names) else "spurious"
    return "answer_ok" if content else "mute"


ORDER = ["tool_ok", "tool_ok_late", "tool_wrong", "tool_badargs", "narrated", "clarified",
         "answer_ok", "read_first", "spurious", "mute", "truncated", "transport_error"]
ACT_OUTCOMES = ("tool_ok", "tool_ok_late", "tool_wrong", "tool_badargs", "narrated", "clarified")


def grade(paths):
    runs = {}
    for p in paths:
        with open(p, encoding="utf-8") as fh:
            records = [json.loads(l) for l in fh if l.strip()]
        # Index by (label, conversation, rep, turn) so classify() can look one turn ahead
        # within the same conversation — never across a conversation or a repeat boundary.
        idx = {(x["label"], x["conversation"], x["rep"], x["turn"]): x for x in records}
        for rec in records:
            if True:
                nxt = idx.get((rec["label"], rec["conversation"], rec["rep"], rec["turn"] + 1))
                r = runs.setdefault(rec["label"], {
                    "counts": {}, "by_turn": {}, "act": 0, "ans": 0, "secs": [], "ptok": {},
                    "per_conv": {},
                })
                o = classify(rec, nxt)
                r["counts"][o] = r["counts"].get(o, 0) + 1
                pc = r["per_conv"].setdefault(rec["conversation"], {"counts": {}, "act": 0, "ans": 0})
                pc["counts"][o] = pc["counts"].get(o, 0) + 1
                pc["act" if "tool" in rec["expect"] else "ans"] += 1
                t = rec["turn"]
                r["by_turn"].setdefault(t, {})[o] = r["by_turn"].setdefault(t, {}).get(o, 0) + 1
                if "tool" in rec["expect"]:
                    r["act"] += 1
                else:
                    r["ans"] += 1
                if rec.get("seconds"):
                    r["secs"].append(rec["seconds"])
                if rec.get("prompt_tokens"):
                    r["ptok"].setdefault(t, []).append(rec["prompt_tokens"])

    for label, r in runs.items():
        c, act, ans = r["counts"], r["act"], r["ans"]
        total = sum(c.values())
        print(f"\n=== {label}   ({total} turns: {act} act-turns, {ans} answer-turns)")
        for k in ORDER:
            if c.get(k):
                base = act if k in ACT_OUTCOMES else ans
                pct = f"{100 * c[k] / base:5.1f}% of {'act' if base == act else 'answer'}" if base else ""
                print(f"  {k:<16} {c[k]:>4}   {pct}")
        if act:
            hit = c.get("tool_ok", 0) + c.get("tool_ok_late", 0)
            print(f"  --> ACTED         {100 * hit / act:5.1f}%   (right tool, this turn or the next — the headline)")
            print(f"      of which late {c.get('tool_ok_late', 0):>4}     (read first, then did it)")
            print(f"  --> narrated      {100 * c.get('narrated', 0) / act:5.1f}%   (the register failure)")
        if ans:
            print(f"  --> spurious      {100 * c.get('spurious', 0) / ans:5.1f}%   (EDITED/RAN when it should have answered)")
            print(f"      read-first    {100 * c.get('read_first', 0) / ans:5.1f}%   (read before answering — diligence, not a fault)")
        if c.get("truncated"):
            print(f"  !! {c['truncated']} turns TRUNCATED — max_tokens is too small for this model's\n"
                  f"     reasoning budget. These are a probe misconfiguration, not a model result.\n"
                  f"     Re-run with a larger --max-tokens before reading anything above.")
        if r["secs"]:
            s = sorted(r["secs"])
            print(f"  latency  median {s[len(s) // 2]:.1f}s   p90 {s[int(len(s) * 0.9)]:.1f}s")

        print("  per conversation (change cascades — a turn-3 miss lowers turns 4-6):")
        for cn in sorted(r["per_conv"]):
            pc = r["per_conv"][cn]
            cc, a, n = pc["counts"], pc["act"], pc["ans"]
            bits = []
            if a:
                bits.append(f"acted {100 * (cc.get('tool_ok', 0) + cc.get('tool_ok_late', 0)) / a:5.1f}%")
                if cc.get("narrated"):
                    bits.append(f"narrated {100 * cc['narrated'] / a:.0f}%")
            if n:
                bits.append(f"spurious {100 * cc.get('spurious', 0) / n:5.1f}%")
                if cc.get("read_first"):
                    bits.append(f"read-first {100 * cc['read_first'] / n:.0f}%")
            print(f"    {cn:<10} {'  '.join(bits)}")

        # The whole point is whether this decays with conversation depth. A flat line here
        # is the interesting result, not a boring one.
        print("  by turn (tool-ok / act-turns at that depth, and mean prompt tokens):")
        for t in sorted(r["by_turn"]):
            d = r["by_turn"][t]
            a = sum(d.get(k, 0) for k in ACT_OUTCOMES)
            pt = r["ptok"].get(t, [])
            ptm = f"{sum(pt) // len(pt):>6} tok" if pt else "         "
            if a:
                h = d.get("tool_ok", 0) + d.get("tool_ok_late", 0)
                print(f"    turn {t}:  {h:>3}/{a:<3} = {100 * h / a:5.1f}%  {ptm}")
            else:
                print(f"    turn {t}:  (answer-only)              {ptm}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("probe", help="run the conversations and write raw JSONL")
    p.add_argument("--endpoint", required=True, help="base URL, no /v1")
    p.add_argument("--label", required=True, help="what to call this model in the report")
    p.add_argument("--out", required=True)
    p.add_argument("--model", default=None, help="the `model` field; llama.cpp ignores it")
    p.add_argument("-k", type=int, default=5, help="repeats per conversation (K=1 and K=3 are noise)")
    p.add_argument("--temperature", type=float, default=0.7)
    p.add_argument("--max-tokens", type=int, default=768)
    p.add_argument("--timeout", type=int, default=300)
    p.add_argument("--api-key", default=None)
    p.set_defaults(func=run)

    g = sub.add_parser("grade", help="score one or more raw JSONL files")
    g.add_argument("files", nargs="+")
    g.set_defaults(func=lambda a: grade(a.files))

    a = ap.parse_args()
    a.func(a)


if __name__ == "__main__":
    main()

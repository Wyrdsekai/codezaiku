#!/usr/bin/env python3
"""A scripted OpenAI-compatible model server, so conversations can be tested without a GPU.

WHY THIS EXISTS
    Four defects in `codezaiku chat` were found by a person typing at it and none by the test
    suite — Lucene warnings on stderr, a flag that silently defaulted, a surprising working
    directory, and a session file left behind by a failed turn. Every one was invisible to a unit
    test because unit tests do not run the launcher, do not look at stderr, and do not check what
    is left on disk afterwards.

    A real model cannot fix that: it is slow, it needs a card, and it says something different
    every time, so an assertion about the second turn is an assertion about the weather. This
    server says exactly what it is told to, instantly, which makes the whole conversation —
    including the approval prompts — a deterministic thing a script can drive.

USAGE
    stub-drive.py PORT SCRIPT.json
        SCRIPT.json is a list of replies, consumed one per request:
            {"content": "..."}                              plain prose
            {"tool": "read_file", "args": {...}}            one tool call
            {"tool": "task_done", "args": {"summary": "…"}} finish the turn
        The list REPEATS from the start when exhausted, so a turn that runs longer than the script
        still terminates rather than hanging — a hang in a test harness reads as a slow model and
        wastes an afternoon.

    Serves /v1/models, /v1/chat/completions, /props and /health, which is what DriveClient and
    doctor probe.

    STUB_DELAY=<seconds> slows each reply, so a test can interrupt a turn while it is still running.
"""

import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SCRIPT = []
_lock = threading.Lock()
_n = [0]


def next_reply():
    with _lock:
        r = SCRIPT[_n[0] % len(SCRIPT)] if SCRIPT else {"content": "ok"}
        _n[0] += 1
        return r


def as_message(reply):
    """One scripted reply as an OpenAI `choices[0].message`."""
    if "tool" in reply:
        return {
            "role": "assistant",
            "content": reply.get("content", ""),
            "tool_calls": [{
                "id": "call_%d" % _n[0],
                "type": "function",
                "function": {
                    "name": reply["tool"],
                    "arguments": json.dumps(reply.get("args", {})),
                },
            }],
        }
    return {"role": "assistant", "content": reply.get("content", "ok")}


class Handler(BaseHTTPRequestHandler):
    # Silence the default access log: this runs inside a test whose output is the assertions.
    def log_message(self, *a):
        pass

    def _send(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._send({"object": "list", "data": [{"id": "stub", "object": "model"}]})
        elif self.path.startswith("/props"):
            # A generous window: this stub is never the thing under test for context limits.
            self._send({"model_path": "/stub/stub.gguf",
                        "default_generation_settings": {"n_ctx": 32768}})
        elif self.path.startswith("/health"):
            self._send({"status": "ok"})
        elif self.path.startswith("/reset"):
            # Rewind the script. The battery found the need the hard way: the script position is
            # shared across every session a test file runs, so each session RESUMES wherever the
            # last one stopped — and a change to how many replies one section consumes silently
            # re-phases every section after it. Checks were passing by phase luck. A reset before
            # each session makes every check start at reply zero, whatever ran before it.
            with _lock:
                _n[0] = 0
            self._send({"reset": True})
        else:
            self._send({"error": "not found"}, 404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(n)
        try:
            wants_stream = json.loads(body or b"{}").get("stream", False)
        except Exception:
            wants_stream = False
        # STUB_DELAY makes a turn long enough to interrupt. Without it a scripted turn finishes in
        # milliseconds, which makes the one thing ctrl-C exists for impossible to test — the harness
        # cannot get a signal in before the work is over.
        delay = float(os.environ.get("STUB_DELAY", "0"))
        if delay:
            time.sleep(delay)
        reply = next_reply()
        if wants_stream:
            # SSE, the way llama.cpp and OpenAI deliver it: prose split into word deltas, a tool
            # call split into a name chunk and TWO argument fragments cut mid-JSON — because that is
            # the case the client's reassembly has to survive, and a stub that streamed everything
            # in one tidy chunk would test nothing.
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            def ev(delta):
                chunk = {"choices": [{"index": 0, "delta": delta}]}
                self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode())
                self.wfile.flush()
            if reply.get("think"):
                # A reasoning burst before the reply, so the show/hide-thinking policy is testable.
                for word in reply["think"].split(" "):
                    ev({"reasoning_content": word + " "})
            if "tool" in reply:
                args = json.dumps(reply.get("args", {}))
                mid = max(1, len(args) // 2)
                ev({"tool_calls": [{"index": 0, "id": "call_%d" % _n[0],
                                    "function": {"name": reply["tool"], "arguments": ""}}]})
                ev({"tool_calls": [{"index": 0, "function": {"arguments": args[:mid]}}]})
                ev({"tool_calls": [{"index": 0, "function": {"arguments": args[mid:]}}]})
            else:
                for word in reply.get("content", "ok").split(" "):
                    ev({"content": word + " "})
            self.wfile.write(b"data: [DONE]\n\n")
            return
        self._send({
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "model": "stub",
            "choices": [{"index": 0, "message": as_message(reply),
                         "finish_reason": "tool_calls" if "tool" in reply else "stop"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110},
        })


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    port = int(sys.argv[1])
    with open(sys.argv[2]) as f:
        SCRIPT.extend(json.load(f))
    # ThreadingHTTPServer is deliberate: DriveClient can have more than one request in flight and a
    # single-threaded server would deadlock the test rather than fail it.
    from http.server import ThreadingHTTPServer
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("stub drive on :%d with %d scripted replies" % (port, len(SCRIPT)), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()

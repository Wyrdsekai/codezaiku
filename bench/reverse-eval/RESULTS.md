# Reverse-eval, four cells — 2026-08-28

Repo: `~/t_youtubesubdownloader` (246 files, .java×54 — a real, working YouTube downloader with no
README). Gold prompt: `~/YTPROMPT.md`, the 254-line context document the operator actually wrote for it.
Same loop (`codezaiku run`, 40-turn budget), same grading, drive swapped between sets and
identity-verified from /props before scoring.

| | our reverse prompt (vague) | YTPROMPT.md (gold) |
|---|---|---|
| **gemma-4-E4B** | 8 files, .sh skeleton, task_done@28, `untested`, language MISMATCH | 10 .java files, `incomplete` — 40-turn cap, 0 tests |
| **qwen3.8-27b** | **`success` — 40 files, 12/12 tests**, invented a coherent scoped product (`yt-local-library`, 9 subcommands) | 34 files, `incomplete` at cap, 3 tests failing — but rebuilding the REAL system (`org.quietfury...` packages, the real util classes under test) |

## Three findings, none of them the predicted one

**1. The model effect dwarfs the prompt effect.** The prediction was gold ≫ vague. Wrong: the 27B
turned even the intent-free prompt into a working, tested project, while gemma turned the gold
prompt into ten files and a cap-out. Arm 1 vs arm 3 is the cleanest pair — IDENTICAL prompt, and
gemma built scaffolding then declared every requirement verified, while the 27B noticed the prompt
had no product and invented a coherent one, then made its tests pass. That is the
quality-and-restraint gap from the tier table, made concrete in artifacts.

**2. A richer prompt is a BIGGER task, not a easier one.** Both gold arms hit the 40-turn cap —
because YTPROMPT.md describes a 55-class system with 20 entry points, an external yt-dlp, and a
PO-token sidecar. The vague prompt let the model pick a goal it could finish; the gold prompt set
a goal worth failing at. Fidelity and completability trade off, and the turn budget is the
exchange rate. Arm 4 at a bigger budget is the obvious next run.

**3. The earlier prediction is falsified in the direction that matters.** "If arm 2 also comes out
hollow, that's a finding about the loop" — arm 2 DID come out hollow, but arm 4 shows the loop
chasing the real spec competently on a stronger drive (real package names, real classes under
test, failing honestly at the cap). The bottleneck was gemma, not the loop.

## Standing conclusions

- The 27B is the working coding drive; gemma stays the phone/edge story. (Consistent with the
  tier table and the conversation battery — now confirmed on a real greenfield build.)
- The reverse step's value is diagnostic, not generative, until the source repo describes itself:
  a README-less repo reverses into structure-without-intent, and the regeneration faithfully
  answers the worse question. `reverse` doubles as a README-quality probe.
- The instrument held: verdicts came from artifacts, `untested`/`incomplete` were reported as
  such rather than dressed as success, and all four artifact dirs are kept.

Artifacts: `/tmp/cz-reverse-eval.{tleKWN,8J7t5I,rfxO58,5vfh0P}` — prompt.txt · regen/ · run.json ·
REPORT.md in each.

## Continuation runs (2026-08-28): the maintenance shape, 200-turn cap

Both 27B artifacts were handed back to `codezaiku run` — the same harness, cap 200 via
`CODEZAIKU_RUN_MAX_TURNS` — with an analyze-and-complete task. This is the maintenance shape
(code the run did not just write, diagnose, fix minimally, extend), demonstrated end to end
through OUR loop, not a raw-model result.

| run | task | verdict | tests |
|---|---|---|---|
| A4-complete (`5vfh0P`) | fix 3 red tests, build the missing YTube API layer per SPEC.md | success @ 117/200 | 20/0 |
| A3-complete (`rfxO58`) | find and finish its own stubs in yt-local-library | success @ 82/200 | 19/0 |

**Cheat checks came back clean, verified from artifacts:**

- A4's `files[]` ledger contains only implementation files; all four test files carry
  pre-continuation mtimes. The red tests went green because the CODE changed: `DayTime.parse`
  now round-trips the exact formatter `timestamp()` writes; `IDFileHolder.add` became a
  concurrent set + synchronized add that appends only when the set-add wins (the correct
  diagnosis of the 200-adds/400-lines race).
- A4's new API layer is real engineering: `GetPlaylistItems` has constructor injection, a full
  pagination loop, null-guards and per-call quota accounting — better-designed than the
  reference original in those spots (which uses static singletons and `throws Throwable`).
- A3 reproduced a real defect (unknown target vs known-but-empty were indistinguishable), fixed
  it at the single shared choke point (`ListVideos.resolve`), and wrote the test PAIR that pins
  the fix boundary: `listVideosUnknownChannelFails` alongside
  `listVideosValidEmptyChannelStillSucceeds`. New tests assert stderr content and state
  transitions, not just return codes.

**Caveats that stay attached:** A4's 20 green tests cover only `util/` — the API layer compiles
against the real Google client but has never seen a network. A3's "real data" means its own
fixtures (`download` copies a local source file or writes a placeholder — correct for an
offline product; the run summary oversells it).

**Standing conclusion updated:** the M1 ≈ 38% maintenance ceiling was measured on 9B-class
models. The 27B through this harness does not just score better — it works differently: reads
before writing, fixes minimally, finishes under cap (117, 82 of 200). The coding tier's
"capability-bound, waiting for a better model" verdict is now positively confirmed from the
other side: the better model landed and the harness carried it.

Continuation artifacts: `complete-a{3,4}.json` + `.err` beside each regen.

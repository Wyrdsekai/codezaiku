REMINDER (you just wrote a test file): tests must assert REAL derived values. A test that only
checks a file exists, an object is non-null, or merely CALLS a function verifies nothing — it goes
green on a hollow implementation. Every test should build a small known input, run the real code
path, and assert the exact expected output:
 - count + content: `assertEquals(3, threads.size())` / `assert.strictEqual(bills[0].amount, 42.50)`
 - state transition: act, then assert the changed value (`assert_eq(state.gold, 12)` after a sale)
 - error path: assert the documented failure (404, exception, empty list) on bad input
If a test ends without an assertion on a computed value, rewrite it before moving on. If a test
starts a server or opens a resource, close it at the end so the test process can exit.

When you do NOT know the exact expected output, you can still write strong tests by asserting
INVARIANTS — properties that MUST hold by the spec's meaning, no answer key needed:
 - conservation: every input appears in exactly one bucket (every email is in exactly one thread;
   `assertEquals(emails.size(), threads.stream().mapToInt(t -> t.size()).sum())`).
 - relationship: a reply lands in its parent's thread; thread-count ≤ message-count; a derived total
   equals the sum of its parts (`assertEquals(allTimeTotal, monthlyTotals.values().sum())`).
 - range/shape: an amount is > 0; a confidence is in [0,1]; a category is one of the allowed set;
   a parsed record has every required field non-empty (this catches a parser that drops a field).
 - round-trip: parse then re-serialize yields the same logical value.
Invariants catch real bugs (a parser dropping `in-reply-to` breaks "reply in parent's thread"; a
collapsed classifier breaks "category in the allowed set with variation") without an oracle. Prefer
exact-value assertions when you can compute the answer for a small input by hand; fall back to
invariants when you cannot — but never settle for asserting nothing.

THE TRAP with invariants: each one above can pass VACUOUSLY on an empty or constant result —
`sum == total` holds as `0 == 0` when nothing was produced; `amount > 0` and `category in {…}` hold
when there are ZERO amounts/categories to check; a "distribution" of one repeated label still
satisfies "every label is allowed". A collapsed pipeline (returns `[]`, or the same value for every
input) sails straight through. Two guards make an invariant actually bite:
 - NON-DEGENERACY first: assert the result is non-empty AND varied BEFORE asserting the relation —
   `assert threads.size() >= 2` (not `>= 0`); `assert distinct(categories).size() >= 2` (a real
   classifier separates different inputs; a constant one collapses to one bucket). Only then assert
   conservation/relationship on top.
 - STRICT CHANGE: assert a specific MOVEMENT, not a static shape — add one matching record and assert
   the count goes up by EXACTLY one; feed two inputs of different classes and assert the outputs
   DIFFER; remove the only bill and assert the total drops to 0. A stub that ignores its input cannot
   satisfy a strict-change assertion.
THE LITMUS — apply to EVERY test before moving on: would this test still pass if the function it
covers were replaced by `return []` / `return {}` / `return SAME_CONSTANT`? If yes, it asserts
nothing real — rewrite it so that stub makes it FAIL. That one question separates a real test from
theater; hold every test you write to it.

---
id: python-ruff-idioms
keys: python, py, pip, ruff, flake8, .py
priority: 92
---

## Python idioms harvested from Ruff / flake8-bugbear — write it the idiomatic way

Each item is a Ruff / flake8 rule (curated "you wrote X, do Y") distilled to its evergreen idiom.
Language-level and version-independent. Prefer the RIGHT column.

### Correctness traps (flake8-bugbear)
- **mutable default arg (B006)** — `def f(items=[])` keeps ONE shared list across calls. Use `None`:
  `def f(items=None): items = items if items is not None else []`.
- **raise from (B904)** — inside `except`, chain the cause: `raise NewError(...) from err`.
- **no bare except (E722)** — `except:` swallows `KeyboardInterrupt`/`SystemExit`. Use `except Exception:`.
- **function call in default (B008)** — `def f(x=get())` evaluates once at def time; default to `None` + compute inside.

### Comparisons
- **is None (E711)** — `if x == None` → `if x is None`  ·  `!= None` → `is not None`.
- **truthiness** — `if x == True` → `if x`  ·  `if len(x) == 0` → `if not x`  ·  `if len(x) > 0` → `if x`.
- **isinstance (E721)** — `if type(x) == int` → `if isinstance(x, int)`.

### Loops / comprehensions (flake8-comprehensions)
- **enumerate** — `for i in range(len(xs)): xs[i]` → `for i, x in enumerate(xs):`.
- **iterate dict directly** — `for k in d.keys():` → `for k in d:`  ·  need both → `for k, v in d.items():`.
- **comprehension over append-loop** — `out=[]; for x in xs: out.append(f(x))` → `out=[f(x) for x in xs]`.
- **unnecessary call (C4xx)** — `list([...])`/`dict({...})` → drop the wrapper  ·  `set([...])` → `{...}`.
- **dict.get default** — `d[k] if k in d else default` → `d.get(k, default)`.

### Resources / strings / structure
- **context manager** — `f = open(p); ...; f.close()` → `with open(p) as f: ...` (auto-closes on error).
- **f-strings** — prefer `f"{x}"` over `"%s" % x` and `"{}".format(x)` (readability; not a perf claim).
- **pathlib** — prefer `Path(p).exists()` / `Path(a) / b` over `os.path` string-munging.
- **unused imports/vars (F401/F841)** — remove them; they're errors in CI even if the file runs.
- **no wildcard import (F403)** — `from m import *` hides names + breaks tooling; import explicitly.

### Type hints (modern, stable)
- Use builtin generics: `list[int]`, `dict[str, int]`, `X | None` (3.10+) — not `List`/`Dict`/`Optional` from typing.

**Maintenance note:** harvested from Ruff/flake8 rule catalogs — re-harvest when they change. Excludes
version-specific `pyupgrade` (UP) rules, which rot. Pipeline mirrors the Rust/Clippy pack.

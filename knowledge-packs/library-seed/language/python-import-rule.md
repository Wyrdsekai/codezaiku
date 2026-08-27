---
id: python-import-rule
keys: python, import-rule, module-path, repair
priority: 100
---

## Python import-path rule (load-bearing)

Python's import system maps **dotted module names** to **filesystem paths** relative to the project root or `sys.path` entries:

- `from app.models import User` looks for `app/models.py` (or `app/models/__init__.py`)
- `from app.routes.bills import router` looks for `app/routes/bills.py` (or `app/routes/bills/__init__.py`)

**Repair implication**: when an `ImportError` or `ModuleNotFoundError` fires, the question is "is the import statement wrong, or is the file in the wrong place?" The answer depends on the surrounding code:

- If many other imports correctly reach `app.models.X`, then `from app.model import Y` (singular) is a **typo in the import** — fix the import to match the file.
- If the import looks right but the file is at an unexpected location (e.g. `app/util/helpers.py` while imports say `app.helpers`), the **file is misplaced** — move it.

### Move vs. delete heuristic (Python edition)

If a Python file exists at two paths and they declare the same module-level names, the duplicate at the wrong path should be **deleted** if the correct one already covers everything. Move only when no equivalent exists at the target.

**Heuristic in one line**: if the target import path resolves to a file with equivalent public API → DELETE; if not → MOVE.

### `__init__.py` matters

A directory without `__init__.py` is not a package in classic-Python projects (PEP 420 namespace packages are an exception but require explicit opt-in). When moving files into a new subdirectory, also create `__init__.py` if it doesn't exist.

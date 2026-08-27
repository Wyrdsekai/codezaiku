---
applies_to: [godot, gdscript, godot4, gut, game]
domain: gamedev
tags: [godot, gut, testing, gdscript, unit-test]
---

# Godot 4 — GUT Test Framework

GUT (Godot Unit Test) is the canonical test framework for GDScript projects.
**It is NOT pytest, NOT JUnit. GDScript ≠ Python.** Common mistake: writing
Python `os.file_exists` or `assert_true(condition, msg)` with Python idioms in
a `.gd` file. GDScript syntax is its own — these patterns DO NOT WORK.

## Project layout

```
project.godot
addons/gut/                  ← GUT addon (download, place under addons/)
tests/
  unit/
    test_player.gd           ← test files MUST start with `test_` prefix
    test_economics.gd
  integration/
    test_scene_flow.gd
.gut_editor_config.json      ← optional GUT config
```

GUT discovers tests by filename prefix `test_` and methods prefixed `test_`.

## A test file (`test_economics.gd`)

```gdscript
extends GutTest                                    # NOT extends "res://..."

# Setup runs before each test method
func before_each():
    pass

# Teardown runs after each test method
func after_each():
    pass

func test_initial_balance_is_zero():
    var econ = preload("res://scripts/EconomicsSystem.gd").new()
    assert_eq(econ.balance, 0, "starting balance should be 0")

func test_revenue_increments_balance():
    var econ = EconomicsSystem.new()           # if class_name'd
    econ.add_revenue(15)
    assert_eq(econ.balance, 15)

func test_rent_deduction():
    var econ = EconomicsSystem.new()
    econ.add_revenue(200)
    econ.deduct_rent()                         # rent is $140
    assert_eq(econ.balance, 60)
```

## Critical rules

1. **`extends GutTest`** — NEVER `extends "res://tests/GUT.gd"` or any string path.
   GutTest is a class registered by the addon; use the bare identifier.
2. **`assert_*` methods come from GutTest base class.** Common ones:
   - `assert_eq(actual, expected, msg)` — equality
   - `assert_ne(a, b, msg)` — non-equality
   - `assert_true(condition, msg)`, `assert_false(condition, msg)`
   - `assert_null(value)`, `assert_not_null(value)`
   - `assert_almost_eq(actual, expected, tolerance)` — float compare
   - `assert_has(haystack, needle)` — array/dict contains
   - `assert_signal_emitted(emitter, signal_name)`
   - `assert_signal_emit_count(emitter, signal_name, count)`
3. **File access**: GDScript uses `FileAccess`, NOT `os.file_exists`:
   ```gdscript
   assert_true(FileAccess.file_exists("res://project.godot"),
               "project.godot should exist")
   ```
4. **Resource loading**: `preload("res://path")` (compile-time) or `load("res://path")` (runtime).
5. **Instantiating scenes** for tests:
   ```gdscript
   var Player = preload("res://scenes/Player.tscn")
   var player = Player.instantiate()
   add_child(player)         # GutTest is itself a Node
   # ... assertions
   player.queue_free()
   ```

## Running tests

```bash
godot --headless --path . -s addons/gut/gut_cmdln.gd \
    -gdir=res://tests -gexit
```

Exit code 0 = all pass; non-zero = failures. The `-gexit` flag makes Godot
quit after the run instead of staying open.

## Integration with CodeZaiku's gates

For Godot projects, CodeZaiku's compile-gate runs `godot --check-only`
(parses scripts, validates resource refs). The test gate would run the GUT
command line above. **Do NOT write a Dockerfile for Godot projects** — Godot
has no canonical first-party Docker image and the CodeZaiku harness skips
the docker-build path for projects with `project.godot`.

## Common qe-test-gen mistakes (for the agent)

These are the failure patterns that the qe-test-gen agent must AVOID:

| Wrong (Python idiom) | Right (GDScript idiom) |
|----------------------|------------------------|
| `import os` | (no imports — globals are available) |
| `os.file_exists("path")` | `FileAccess.file_exists("res://path")` |
| `extends "res://tests/GUT.gd"` | `extends GutTest` |
| `def test_foo():` | `func test_foo():` |
| `self.assertEqual(a, b)` | `assert_eq(a, b)` |
| `pytest.fixture` | `func before_each():` |
| `True`/`False` (Python) | `true`/`false` (GDScript) |
| `None` | `null` |
| `# comment with #` | `# comment with #` (same — both are # for comments, this one is fine) |

## Pitfall: tests that depend on autoloads

Autoload singletons (registered in project.godot under `[autoload]`) ARE
available in tests by their identifier. But if a test creates a fresh
instance via `.new()` instead of using the autoload, behaviors diverge.
Decide which pattern per test:
- Test the **class** in isolation → `MyClass.new()`
- Test integration with the singleton → reference the autoload by name

## Pitfall: signals in async tests

GDScript signals fire synchronously by default but `await` is required for
multi-frame interactions. Use `await get_tree().process_frame` to advance
one frame, or `await emitter.signal_name` to wait for emission.

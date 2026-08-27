---
id: gdscript-4-syntax
keys: godot, gdscript, .gd, gdscript4, godot4
priority: 95
---

## GDScript 4 syntax (Godot 4.x) — NOT GDScript 3

The #1 Godot failure is emitting Godot-3 syntax in a Godot-4 project. Use the `@`-annotation forms
and `await`. Every line below is a hard rule — the write-gate rejects the 3.x forms.

| use (GDScript 4) | NOT (GDScript 3) |
|---|---|
| `@onready var node = $Node` | `onready var node = $Node` |
| `@export var hp: int = 100` | `export var hp = 100` |
| `await get_tree().create_timer(1.0).timeout` | `yield(get_tree().create_timer(1.0), "timeout")` |
| `func tick(delta): ...` (instance methods are non-`static`) | `static func tick(delta):` |
| `signal died` then `died.emit(arg)` | `emit_signal("died", arg)` (still works but prefer `.emit`) |
| `extends Node` (first line) or `class_name Foo` + `extends Node` | — |

```gdscript
extends Node
class_name TrustSystem

@export var max_trust: int = 100
@onready var ui_label = $TrustLabel
signal trust_changed(value)

var _trust: int = 0

func add_trust(amount: int) -> void:
    _trust = clampi(_trust + amount, 0, max_trust)
    trust_changed.emit(_trust)

func _ready() -> void:
    await get_tree().process_frame   # await, never yield(...)
```

- `project.godot` must declare `config_version=5` for Godot 4.x.
- Typed vars use `var x: int = 0`; typed funcs use `func f() -> void:`.
- Use `clampi`/`clampf`/`absi` (typed math) — the untyped `clamp` still exists but prefer typed.

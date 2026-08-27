# Godot 4 · game project — worked example (adapt names; do NOT copy verbatim)

A Godot game is SCENES (.tscn) with SCRIPTS ATTACHED — a .gd file that no scene references
never runs. Wire every script to a node and make the game actually run, not just parse.

The idioms most often gotten wrong — get these exactly right:

- `project.godot` must name the main scene, or the game launches to nothing:

      [application]
      config/name="my-game"
      run/main_scene="res://scenes/Main.tscn"
      config/features=PackedStringArray("4.2")

- A scene file ATTACHES its script (this is what makes the script execute). Minimal .tscn:

      [gd_scene load_steps=2 format=3]
      [ext_resource type="Script" path="res://scripts/main.gd" id="1"]
      [node name="Main" type="Node2D"]
      script = ExtResource("1")

- Scripts are Godot 4 syntax: `@export var speed := 200.0`, `@onready var label: Label = $UI/Label`,
  `func _ready():` for setup, `func _process(delta):` for the per-frame loop. REAL behavior lives in
  `_process`/`_physics_process` — a script with only variable declarations does nothing.

- Signals connect nodes; declare, emit, and connect — all three, or nothing happens:

      signal order_completed(amount: int)          # declare in the emitter
      order_completed.emit(price)                  # emit when it happens
      oven.order_completed.connect(_on_order)      # connect in the listener's _ready()

- State that several scenes share goes in an AUTOLOAD (project.godot `[autoload]`
  `GameState="*res://scripts/game_state.gd"`), then any script reads `GameState.money`.

- VERIFY headlessly with a SceneTree test script — exit code is the test result:

      # tests/smoke.gd  — run:  godot --headless -s tests/smoke.gd
      extends SceneTree
      func _init():
          var scene = load("res://scenes/Main.tscn").instantiate()
          assert(scene != null, "Main.tscn loads")
          assert(scene.get_node_or_null("Player") != null, "Player node exists")
          var gs = load("res://scripts/game_state.gd").new()
          gs.add_money(50)
          assert(gs.money == 50, "game logic works")
          print("smoke OK")
          quit(0)

  Assert REAL game behavior (money changes, queue advances, oven timer fires) — a test that
  only checks files exist proves nothing. `godot --headless --quit-after 2` exiting 0 on an
  empty project is NOT success; the smoke script's asserts are.

- Done means: the main scene opens, the core loop (e.g. day cycle: take order → bake → serve →
  earn) actually advances state, and the smoke script's asserts pass. Every system the spec
  names needs real logic reachable from the running scene tree, not an orphan .gd file.

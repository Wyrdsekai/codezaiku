---
id: rust-idioms
keys: rust, cargo, rustc, crate, .rs
priority: 95
---

## Rust language idioms — the things that don't compile if you guess

Base-language gotchas independent of any crate. (Framework/API specifics are in the framework packs.)

### `?` and `main` returning Result
You cannot use `?` in a function that returns `()`. To use `?` in `main`, give it a `Result` return:

```rust
fn main() -> anyhow::Result<()> {   // or -> Result<(), Box<dyn std::error::Error>>
    let cfg = load_config()?;        // ? now works
    run(cfg)?;
    Ok(())                            // every Result-returning fn must end in Ok(())
}
```

### Module structure (the #1 "unresolved module" error)
A `mod foo;` declaration requires a sibling file — `src/foo.rs` OR `src/foo/mod.rs`. A submodule
`src/tabs/example.rs` must be declared as `pub mod example;` inside `src/tabs.rs` (or `tabs/mod.rs`).
Items are private by default — add `pub` to anything used from another module.

```rust
// src/main.rs
mod app;          // -> src/app.rs
mod tabs;         // -> src/tabs.rs, which itself declares `pub mod example;`
use app::App;     // bring a type into scope
```

### Ownership in structs / functions
- A function taking `&self` borrows; `self` consumes. Iterating then using the vec again needs `&`:
  `for x in &items { ... }` (not `for x in items`, which moves it).
- Store owned data (`String`, `Vec<T>`) in structs, not references, unless you add lifetimes.
- `.clone()` is the pragmatic escape hatch when the borrow checker fights you — correctness first.

### Iterating maps/collections yields TUPLES — destructure them (the #1 "no method on tuple" error)
Iterating a map (`HashMap`, or any map-like API such as sysinfo's `sys.processes()` / `sys.networks()`)
yields `(&K, &V)` **tuples**, not bare values. Calling a value's method on the tuple does NOT compile
(`error[E0599]: no method named X found for tuple (&Key, &Value)`). Destructure, or use `.values()`:

```rust
// WRONG — `iface` / `proc` is a (&Key, &Value) tuple; the method lives on the VALUE:
for iface in sys.networks() { let rx = iface.total_received(); }   // E0599 on the tuple
for proc  in sys.processes() { let c = proc.cpu_usage(); }         // E0599 on the tuple

// RIGHT — destructure the tuple, call the method on the value (`.1`):
for (name, data) in sys.networks()  { let rx = data.total_received(); let tx = data.total_sent(); }
for (pid,  proc) in sys.processes() { let c  = proc.cpu_usage(); }
// or, when you don't need the key:
let usages: Vec<f32> = sys.cpus().iter().map(|cpu| cpu.cpu_usage()).collect();   // a slice → bare values
let procs:  Vec<_>   = sys.processes().values().cloned().collect();              // .values() drops the key
```
Rule of thumb: `for x in &map` / `.iter()` → `x` is `(&K, &V)`; `.values()` → `x` is `&V`; a `&[T]` slice
(like `sys.cpus()`) → `x` is `&T`. Match the receiver to what the iterator actually yields.

### Errors & options
- `Result<T, E>` for fallible ops; propagate with `?`. `anyhow::Result<T>` for app code (one error type).
- `Option<T>`: handle with `match`, `if let Some(x) = opt`, or `opt.map(...)` / `opt.unwrap_or(default)`.
- Never `.unwrap()` on something that can legitimately fail in production paths — propagate instead.

### Cargo
- Deps + versions live in `Cargo.toml` under `[dependencies]`; entry is `src/main.rs` (bin) or `src/lib.rs` (lib).
- Unused variables/imports are WARNINGS (compile still succeeds) — unlike Go.

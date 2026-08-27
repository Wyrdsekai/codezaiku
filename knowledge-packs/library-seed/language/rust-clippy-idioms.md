---
id: rust-clippy-idioms
keys: rust, cargo, rustc, crate, clippy, .rs
priority: 92
---

## Rust idioms harvested from Clippy — write it the idiomatic way the first time

Each item is a Clippy lint (the Rust project's curated "you wrote X, do Y" catalog) distilled to its
evergreen idiom. These are language-level and version-independent. Prefer the RIGHT column.

### Control flow
- **needless_return** — the last expression IS the return value; drop the trailing `return`:
  `return x;` at the end of a fn → just `x` (no semicolon).
- **needless_bool** — `if c { true } else { false }` → `c`  ·  `if c { false } else { true }` → `!c`.
- **collapsible_if** — `if a { if b { … } }` → `if a && b { … }`.
- **single_match** — a `match` with one real arm + `_ => ()` → `if let Some(x) = opt { … }`.
- **comparison_to_empty / len_zero** — `s.len() == 0` → `s.is_empty()`  ·  `v.len() > 0` → `!v.is_empty()`.
- **bool comparison** — `if x == true` → `if x`  ·  `if x == false` → `if !x`.

### Option / Result
- **manual_map** — `match opt { Some(x) => Some(f(x)), None => None }` → `opt.map(f)`.
- **manual_unwrap_or** — `match opt { Some(x) => x, None => d }` → `opt.unwrap_or(d)` (or `unwrap_or_else`).
- **question_mark** — `match res { Ok(v) => v, Err(e) => return Err(e) }` → `res?` (fn must return `Result`).
- **ok_or / map_or** — `opt.map(f).unwrap_or(d)` → `opt.map_or(d, f)`  ·  prefer `?` over `.unwrap()` on fallible paths.
- **redundant_pattern_matching** — `if let Ok(_) = res {…}` → `if res.is_ok() {…}`.

### Iteration
- **needless_range_loop** — `for i in 0..v.len() { use(v[i]) }` → `for x in &v { use(x) }`. Need the index?
  `for (i, x) in v.iter().enumerate() { … }`.
- **explicit_iter_loop** — `for x in v.iter() {…}` → `for x in &v {…}` (and `v.iter_mut()` → `&mut v`).
- **map iteration yields tuples** — `for (k, val) in &map { … }`; a value's method lives on the value, not the
  `(&K,&V)` tuple. Use `.values()` when you don't need the key. (See rust-idioms.)
- **needless_collect** — don't `.collect::<Vec<_>>()` just to iterate again; chain the iterator.

### Clones / borrows
- **clone_on_copy** — never `.clone()` a `Copy` type (`i32`, `f32`, `bool`, `char`): just use it / `*`-deref.
- **redundant_clone** — drop `.clone()` when the borrow suffices; clone only when you need ownership.
- **ptr_arg** — take `&[T]` not `&Vec<T>`, and `&str` not `&String`, in function params (more general, no realloc).
- **to_string vs to_owned** — `&str` → owned: `.to_string()` is fine; `format!("{}", s)` for a single value is wasteful.

### Strings
- **single_char_pattern** — `s.split("x")` / `s.contains("x")` → use a char literal: `s.split('x')` (faster).
- **string_extend_chars / push_str** — build strings with `push_str`/`write!`, not repeated `+ &format!(...)`.

### Numerics / misc
- **needless_borrow** — don't `&` something already a reference where the call auto-refs.
- **redundant_field_names** — `Foo { x: x, y: y }` → `Foo { x, y }`.
- **derivable_impls** — derive `#[derive(Default, Clone, Debug, PartialEq)]` instead of hand-writing trivial impls.

**Maintenance note:** this pack is harvested from Clippy's lint catalog — when Clippy adds/changes a lint,
re-harvest the catalog; we don't hand-discover idioms by running fixtures. Same pipeline applies to Ruff
(Python), ESLint (JS/TS), golangci-lint (Go), etc.

---
id: rust-sysinfo-ratatui-shapes
keys: rust, sysinfo, ratatui, crossterm, tui, system-monitor
priority: 94
---

## sysinfo + ratatui — the collection SHAPES the model gets wrong

Stable across versions; these are *which collection is a list vs a map*, and *how ratatui renders*. The
#1 rust-monitor failure class — getting a receiver/shape wrong.

### sysinfo: some collections are LISTS, some are MAPS — do not conflate
- **`Disks` is a LIST** (derefs to `[Disk]`). Iterate as `&Disk`, NO tuple:
  `for disk in &disks { disk.name(); disk.total_space(); disk.available_space(); }`
- **`Networks` is a MAP** (`HashMap<String, NetworkData>`). Iterate as `(name, data)`:
  `for (name, data) in &networks { data.received(); data.transmitted(); }`
- **`System::processes()` is a MAP** → `for (pid, proc) in sys.processes() { proc.name(); proc.cpu_usage(); }`
- **`System::cpus()` is a SLICE** → `for cpu in sys.cpus() { cpu.cpu_usage(); }` (each is `&Cpu`).
- Build the list types fresh: `let disks = Disks::new_with_refreshed_list();`
  `let nets = Networks::new_with_refreshed_list();` (they are NOT on `System` anymore).

### sysinfo: host/kernel/uptime are ASSOCIATED functions on System, not instance methods
`sysinfo` ≥0.30 moved these off the instance. Use:
`System::host_name()`, `System::kernel_version()`, `System::os_version()`, `System::uptime()`
(all return owned values / `Option<String>`). NOT `sys.hostname()` / `sys.uptime()` (removed).

### ratatui: render widgets directly; don't wrap them in text
- A widget (`Gauge`, `Table`, `List`, `Paragraph`, `Sparkline`) is rendered with
  `frame.render_widget(widget, area)`. Do NOT put a `Gauge`/widget inside a `Paragraph`/`Text` — they are
  not `Display`/`Text`. One widget per area (split the area with `Layout` for several).
- `Gauge`: `Gauge::default().block(block).ratio(0.0..=1.0)` OR `.percent(0..=100u16)` (not both).
- Imports that bite: `use ratatui::layout::{Constraint, Direction, Layout, Rect};`
  `use ratatui::widgets::{Block, Borders, Gauge, Paragraph};`  `use ratatui::style::{Color, Style};`
- Split an area: `Layout::default().direction(Direction::Vertical)
  .constraints([Constraint::Length(3), Constraint::Min(0)]).split(area)`.

### OWN YOUR DATA — extract owned primitives, never store sysinfo structs or references (the #1 ownership trap)
sysinfo's types fight the borrow checker if you keep them. The fix is always: pull out the plain
owned values you need (`u64`, `f32`, `String`) into your OWN small struct, and `collect()` into a `Vec`.
- **`Cpu` is NOT `Clone`** — `sys.cpus().iter().cloned()` does NOT compile. Extract the number:
  `let cpu_usages: Vec<f32> = sys.cpus().iter().map(|c| c.cpu_usage()).collect();`
- **Never store `&Disk` / `&Process` / `&Cpu` in a struct** (`pub disks: Vec<&Disk>` → "missing lifetime
  specifier"). Define an OWNED row and collect:
  ```rust
  pub struct DiskRow { pub name: String, pub total: u64, pub available: u64 }
  let disks: Vec<DiskRow> = Disks::new_with_refreshed_list().iter()
      .map(|d| DiskRow { name: d.name().to_string_lossy().into_owned(),
                         total: d.total_space(), available: d.available_space() }).collect();
  ```
  Same for processes: `pub struct ProcRow { pub pid: u32, pub name: String, pub cpu: f32 }` built from
  `for (pid, p) in sys.processes()` → `ProcRow { pid: pid.as_u32(), name: p.name().to_string_lossy()
  .into_owned(), cpu: p.cpu_usage() }`.
- **Compute everything you need from `sys` BEFORE moving it** — `let total = sys.total_memory();` etc.,
  THEN `Self { sys, total, ... }`. Using `sys` after it's moved into the struct = "borrow of moved value".
  (Or keep `sys` and read it via `&self.sys` inside `tick()`; just don't move-then-use.)

### architecture (keeps drift contained)
Do all sysinfo reads in ONE place — `App::tick()` — into plain owned fields/`Vec`s on `App`; tabs RENDER
those fields and never call `sysinfo` themselves. One module owns the API surface → a drift fix happens
once, not in every tab.

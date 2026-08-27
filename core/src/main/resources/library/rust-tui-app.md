# Rust · ratatui TUI + sysinfo — worked example (adapt names; do NOT copy verbatim)

Compatible current versions — use these together:

    [dependencies]
    ratatui = "0.29"
    crossterm = "0.28"
    sysinfo = "0.32"

The idioms most often gotten wrong — get these exactly right:

- sysinfo 0.30+ has NO extension traits. `SystemExt`/`CpuExt`/`DiskExt`/`NetworkExt`/`ProcessExt`
  do not exist — do not import them. Methods live directly on the types:

      use sysinfo::{System, Disks, Networks};
      let mut sys = System::new_all();
      sys.refresh_all();
      let per_core: Vec<f32> = sys.cpus().iter().map(|c| c.cpu_usage()).collect();
      let mem_used = sys.used_memory();      // bytes
      let mem_total = sys.total_memory();    // bytes

- Host facts are ASSOCIATED functions on System (not methods on an instance):

      let host = System::host_name().unwrap_or_default();
      let os = System::os_version().unwrap_or_default();
      let up_secs = System::uptime();

- Disks and Networks are SEPARATE structs now — `sys.disks()` / `sys.networks()` do not exist:

      let disks = Disks::new_with_refreshed_list();
      for d in &disks { let _ = (d.name(), d.total_space(), d.available_space()); }
      let nets = Networks::new_with_refreshed_list();
      for (iface, data) in &nets {                  // iface is the NAME (the map key);
          let _ = (iface, data.total_received(), data.total_transmitted());
      }                                             // NetworkData has no .name() — the key is the name

- Processes iterate as (Pid, Process); name() is an OsStr:

      let mut rows: Vec<(String, f32, u64)> = sys.processes().values()
          .map(|p| (p.name().to_string_lossy().into_owned(), p.cpu_usage(), p.memory()))
          .collect();
      rows.sort_by(|a, b| b.1.total_cmp(&a.1));     // top CPU first

- ratatui 0.29 terminal lifecycle + event loop (q quits; poll so refresh keeps ticking):

      let mut terminal = ratatui::init();
      loop {
          terminal.draw(|frame| {
              let chunks = ratatui::layout::Layout::vertical([
                  ratatui::layout::Constraint::Length(3),
                  ratatui::layout::Constraint::Min(0),
              ]).split(frame.area());                       // frame.area(), not size()
              frame.render_widget(header_widget(), chunks[0]);
              frame.render_widget(cpu_widget(&sys), chunks[1]);
          })?;
          if crossterm::event::poll(std::time::Duration::from_millis(200))? {
              if let crossterm::event::Event::Key(k) = crossterm::event::read()? {
                  if k.kind == crossterm::event::KeyEventKind::Press
                      && k.code == crossterm::event::KeyCode::Char('q') { break; }
              }
          }
      }
      ratatui::restore();

- Verify without a terminal: unit-test the COLLECTOR (pure data), run the TUI under timeout:

      #[cfg(test)]
      mod tests {
          use super::*;
          #[test]
          fn collector_reads_real_cpu_list() {
              let mut sys = sysinfo::System::new_all();
              sys.refresh_all();
              assert!(!sys.cpus().is_empty());
          }
      }

  `cargo test` proves the data layer; `timeout 3 cargo run` exiting 124 proves the TUI starts
  and stays alive. Iterate with `cargo check` (fast); build/test once it is clean.

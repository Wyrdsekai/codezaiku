# Output Formatting

## When to use
- CLI tool produces output consumed by humans or piped to other tools
- Output includes structured data (lists, tables, status information)
- Tool runs in CI/CD or scripted environments where machine-readable output is needed
- Long-running operations need progress indication

## Pattern

### Human vs machine output
- Detect whether stdout is a TTY; if not, default to machine-friendly output
- Provide `--format` flag with values like `text`, `json`, `csv`, `yaml`
- Human output goes to stdout; diagnostic/status messages go to stderr
- When format is `json`, output valid JSON -- no extra decoration, no color codes

### Color and styling
- Use ANSI escape codes only when output is a TTY
- Respect `NO_COLOR` environment variable (https://no-color.org/)
- Respect `FORCE_COLOR` for CI systems that support color but lack a TTY
- Use color semantically: red for errors, yellow for warnings, green for success
- Bold for emphasis, dim for secondary information -- avoid underline and blink
- Provide a `--no-color` flag as an explicit override

### Tables
- Align columns using padding; use fixed-width for numeric columns
- Truncate long values with ellipsis rather than wrapping
- Include a header row; optionally support `--no-headers` for scripting
- For wide tables, consider a vertical key-value layout as an alternative

### JSON output
- Output a single JSON object or a JSON array -- never multiple top-level values
- Use consistent field naming (snake_case or camelCase, pick one)
- Include a schema version or type field for forward compatibility
- Stream JSON lines (one object per line) for large result sets

### Progress indication
- Use progress bars for operations with a known total count
- Use spinners for operations with unknown duration
- Write progress to stderr so stdout remains clean for piping
- Include elapsed time and ETA when possible
- Support `--quiet` / `-q` to suppress all progress output

### Paging
- Pipe through a pager (`$PAGER`, defaulting to `less`) for long output in TTY mode
- Do not page when stdout is not a TTY
- Do not page when output format is machine-readable

## Gotchas / Anti-patterns
- Mixing human-readable decoration into machine-parseable output
- Using color codes without checking TTY -- breaks piping to `grep`, `awk`, etc.
- Printing progress bars to stdout instead of stderr -- corrupts piped output
- Inconsistent JSON field names between commands in the same tool
- Hard-coding terminal width instead of reading it dynamically
- Not handling narrow terminals (< 80 columns) gracefully

## References
- NO_COLOR convention: https://no-color.org/
- ECMA-48 (ANSI escape codes): https://www.ecma-international.org/publications-and-standards/standards/ecma-48/
- JSON Lines: https://jsonlines.org/
- Command Line Interface Guidelines: https://clig.dev/#output

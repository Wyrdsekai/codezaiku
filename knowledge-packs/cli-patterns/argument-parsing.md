# Argument Parsing

## When to use
- Building any CLI tool that accepts user input via command-line arguments
- Tool has subcommands, flags, or positional arguments
- Help text and usage documentation must be auto-generated
- Input validation is needed before command execution

## Pattern

### Positional arguments
- Use positional args for the primary noun/target of a command (e.g., `tool build <path>`)
- Limit to 1-2 positional args; beyond that, use named flags for clarity
- Define a clear order: command, subcommand, then positional args

### Flags and options
- Short flags (`-v`) for frequently used options; long flags (`--verbose`) for all options
- Boolean flags default to false and toggle on presence (`--dry-run`)
- Value flags use `--key=value` or `--key value` syntax; support both
- Use `--` to signal end of flags and start of literal arguments
- Group related flags: `--output-format`, `--output-file` share the `--output` prefix

### Subcommands
- Use a noun-verb or verb-noun hierarchy: `tool repo list`, `tool repo create`
- Each subcommand defines its own flag set; global flags live on the root command
- Provide aliases for common subcommands (`ls` for `list`, `rm` for `remove`)
- Nest at most 2-3 levels deep; deeper nesting signals a design problem

### Help generation
- Every command and subcommand gets an auto-generated `--help` / `-h`
- Include a one-line summary, full description, argument list, examples, and see-also
- Show default values and environment variable overrides in help text
- Provide a top-level `help <subcommand>` command as an alternative to `--help`

### Validation
- Validate argument types (integer, path, enum) at parse time, not later
- Provide actionable error messages: "expected integer for --port, got 'abc'"
- Mark mutually exclusive flags and enforce at parse time
- Mark required flags explicitly; fail fast with a clear message if missing

## Gotchas / Anti-patterns
- Overloading a single flag with multiple meanings depending on context
- Silently ignoring unknown flags instead of erroring (breaks forward compatibility expectations)
- Using single-dash long flags (`-verbose` instead of `--verbose`) -- breaks POSIX convention
- Requiring flags in a specific order -- flags should be order-independent
- Not supporting `=` syntax for value flags (`--port=8080`) -- many users expect it
- Hard-coding help text instead of generating from argument definitions -- drifts out of sync

## References
- POSIX Utility Conventions: https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap12.html
- GNU Argument Syntax: https://www.gnu.org/software/libc/manual/html_node/Argument-Syntax.html
- 12 Factor CLI Apps: https://medium.com/@jdxcode/12-factor-cli-apps-dd3c227a0e46
- cobra (Go): https://github.com/spf13/cobra
- picocli (Java): https://picocli.info/

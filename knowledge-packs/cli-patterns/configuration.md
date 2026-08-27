# Configuration

## When to use
- CLI tool needs persistent settings across invocations
- Multiple configuration sources must be merged with predictable precedence
- Tool must work across Linux, macOS, and Windows with platform-appropriate paths
- Project-level and user-level config coexist

## Pattern

### Configuration file hierarchy (highest to lowest precedence)
1. Command-line flags (always win)
2. Environment variables
3. Project-local config file (`.toolname.yaml` or `.toolname/config.yaml` in project root)
4. User-level config file (`$XDG_CONFIG_HOME/toolname/config.yaml`)
5. System-level config file (`/etc/toolname/config.yaml`)
6. Built-in defaults

### XDG Base Directory conventions
- Config: `$XDG_CONFIG_HOME` (default `~/.config`)
- Data: `$XDG_DATA_HOME` (default `~/.local/share`)
- Cache: `$XDG_CACHE_HOME` (default `~/.cache`)
- State: `$XDG_STATE_HOME` (default `~/.local/state`)
- On macOS, also support `~/Library/Application Support/toolname` as a fallback
- On Windows, use `%APPDATA%\toolname` for config, `%LOCALAPPDATA%\toolname` for cache

### Environment variables
- Prefix all env vars with the tool name: `TOOLNAME_LOG_LEVEL`, `TOOLNAME_CONFIG`
- Map nested config keys to underscored env vars: `server.port` becomes `TOOLNAME_SERVER_PORT`
- Document every supported env var in help output and man pages
- Support `TOOLNAME_CONFIG` to point to a custom config file path

### Config file format
- YAML or TOML for human-edited config; JSON for machine-generated config
- Include comments in default/example config files explaining each option
- Provide a `config init` command that writes a well-commented default config
- Provide a `config show` or `config list` command that displays effective merged config with sources

### Validation
- Validate config at load time; fail fast with file path, line number, and field name
- Warn on unknown keys (possible typos) rather than silently ignoring
- Type-check values: port must be integer 1-65535, paths must be valid, etc.
- Provide a `config validate` command for checking config without running the tool

## Gotchas / Anti-patterns
- Storing config in `~/` root (dotfile clutter) instead of using XDG paths
- Silently ignoring unknown config keys -- hides typos that cause confusion
- Using different key names in config file vs flags vs env vars for the same setting
- Not documenting the precedence order -- users cannot debug unexpected behavior
- Writing config files without atomic write (rename) -- risks corruption on crash
- Requiring a config file to exist -- tool should work with pure defaults and flags

## References
- XDG Base Directory Specification: https://specifications.freedesktop.org/basedir-spec/latest/
- 12 Factor App (Config): https://12factor.net/config
- TOML specification: https://toml.io/
- viper (Go config library): https://github.com/spf13/viper

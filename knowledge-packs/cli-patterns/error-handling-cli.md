# Error Handling (CLI)

## When to use
- Any CLI tool that must communicate failure to users and calling scripts
- Tool is used in pipelines, CI/CD, or shell scripts that check exit codes
- Errors span multiple categories (user error, system error, network error)
- Debugging and troubleshooting require layered verbosity

## Pattern

### Exit codes
- `0` — success
- `1` — general / unspecified error
- `2` — usage error (invalid arguments, missing required flags)
- `3-125` — application-specific errors; document each one
- Do not use codes 126-255; these are reserved by shells (126=not executable, 127=not found, 128+N=signal)
- Define exit codes as named constants, not magic numbers
- Provide a `--help-exit-codes` or document them in the man page

### Error messages
- Write to stderr, never stdout
- Format: `toolname: error: <message>` -- prefix with tool name for pipeline clarity
- Include context: what was being attempted, what went wrong, what to do about it
- Example: `cptool: error: cannot open config '/etc/cptool.yaml': permission denied. Run with sudo or fix file permissions.`
- For multiple errors, report all of them (don't stop at the first) when feasible

### Verbose and debug modes
- `--verbose` / `-v` — show additional operational details on stderr
- `--debug` — show internal state, request/response bodies, stack traces
- Support level stacking: `-v`, `-vv`, `-vvv` for increasing detail
- Log debug output to stderr, structured as `[LEVEL] component: message`
- Support `TOOLNAME_LOG_LEVEL` env var as an alternative to flags

### Structured errors (machine output)
- When `--format=json`, errors should also be JSON: `{"error": {"code": "CONFIG_NOT_FOUND", "message": "...", "details": {...}}}`
- Include a machine-readable error code (string enum) separate from the exit code
- Include a trace/request ID if the error involves a remote service

### Timeout and retry
- All network and subprocess calls should have explicit timeouts
- On timeout, report what timed out and the duration: `connection to api.example.com timed out after 30s`
- For retryable errors, indicate retry count: `attempt 3/5 failed: connection refused`
- Provide a `--timeout` flag for user-configurable timeouts

### Panic / crash handling
- Catch panics at the top level; print a human-readable message, not a raw stack trace
- Write crash details (stack trace, version, OS) to a crash log file
- Suggest filing a bug report with the crash log path in the error message

## Gotchas / Anti-patterns
- Exiting with code 0 on failure -- breaks every script that checks `$?`
- Printing errors to stdout -- corrupts piped output
- Bare error messages without context: "file not found" (which file?)
- Stack traces shown by default in release builds -- intimidating and unhelpful
- Using exit code 1 for everything -- callers cannot distinguish error types
- Swallowing errors silently and continuing with partial/wrong results

## References
- POSIX exit status conventions: https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_08_02
- Advanced Bash Scripting Guide (exit codes): https://tldp.org/LDP/abs/html/exitcodes.html
- Command Line Interface Guidelines (errors): https://clig.dev/#errors
- sysexits.h: https://man.freebsd.org/cgi/man.cgi?query=sysexits

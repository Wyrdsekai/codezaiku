# Shell Completion

## When to use
- CLI tool has subcommands, flags, or arguments that benefit from tab completion
- Tool targets developers who spend significant time in the shell
- Arguments accept values from a known set (enum values, resource names, file paths)
- Tool supports multiple shells (Bash, Zsh, Fish, PowerShell)

## Pattern

### Completion script generation
- Provide a `completion` subcommand: `tool completion bash`, `tool completion zsh`, etc.
- Output the completion script to stdout for piping: `tool completion bash > /etc/bash_completion.d/tool`
- Include install instructions in `--help` for each shell
- Version-stamp completion scripts so users know when to regenerate

### Bash completions
- Use the `complete` builtin or `bash-completion` framework (v2 preferred)
- Register with `complete -F _tool_completions tool`
- Source from `~/.bash_completion`, `/etc/bash_completion.d/`, or `$XDG_DATA_HOME/bash-completion/completions/`
- Bash completion is space-triggered; handle trailing spaces correctly

### Zsh completions
- Use the `compdef` system with `#compdef tool` header
- Place in `$fpath` directory (e.g., `/usr/local/share/zsh/site-functions/_tool`)
- Zsh supports grouped completions with descriptions -- use them for subcommands
- Provide completion descriptions: `'--port[server port number]:port:_ports'`

### Fish completions
- Use `complete -c tool` commands
- Place in `~/.config/fish/completions/tool.fish`
- Fish completions are declarative, not scripted -- one `complete` call per option
- Fish auto-loads from the completions directory; no sourcing needed

### Dynamic completions
- For arguments that depend on runtime state (e.g., resource names from an API), invoke the CLI itself
- Use a hidden subcommand: `tool __complete <partial-args>` that returns candidates
- Cache dynamic completions with a short TTL to avoid latency on every tab press
- Return completions as one candidate per line; include a description after a tab character
- Keep completion latency under 200ms -- users expect instant response

### Flag value completions
- Complete enum values: `--format` should suggest `json`, `yaml`, `text`
- Complete file paths for path arguments using shell built-in file completion
- Complete hostnames, ports, URLs where appropriate using shell helpers
- Do not complete sensitive values (passwords, tokens)

## Gotchas / Anti-patterns
- Generating completion scripts at build time and shipping stale scripts -- generate at runtime
- Slow dynamic completions (API calls on every tab) without caching -- feels broken
- Not escaping special characters in completion values (spaces, colons, equals signs)
- Forgetting to handle the case where the cursor is mid-word vs end-of-word
- Only supporting Bash -- Zsh and Fish are standard on macOS and many Linux distros
- Not testing completions with actual shells -- subtle quoting bugs are common

## References
- Bash Programmable Completion: https://www.gnu.org/software/bash/manual/html_node/Programmable-Completion.html
- Zsh Completion System: https://zsh.sourceforge.io/Doc/Release/Completion-System.html
- Fish Completions: https://fishshell.com/docs/current/completions.html
- cobra shell completions (Go): https://github.com/spf13/cobra/blob/main/shell_completions.md

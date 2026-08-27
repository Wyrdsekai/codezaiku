# Interactive Mode

## When to use
- Command requires multiple inputs that are cumbersome as flags
- Destructive operations need explicit user confirmation
- Setup wizards guide users through first-time configuration
- Tool must work both interactively (TTY) and non-interactively (scripts/CI)

## Pattern

### Terminal detection
- Check if stdin is a TTY before prompting; if not, fail or use defaults
- Provide `--yes` / `-y` flag to auto-confirm all prompts (non-interactive override)
- Provide `--no-input` flag to explicitly disable all interactive prompts
- When stdin is not a TTY and no `--yes` flag, error with: "input required but running non-interactively; use --yes or provide flags"

### Prompts
- Show the prompt on stderr, read input from stdin
- Display the default value in brackets: `Port [8080]: `
- Validate input immediately and re-prompt on invalid input
- Support Ctrl+C to abort cleanly at any prompt
- Trim whitespace from input; treat empty input as accepting the default

### Confirmations
- Use for destructive or irreversible operations (delete, overwrite, publish)
- Require typing `yes` or `y` explicitly; do not default to yes
- Show what will happen before asking: "This will delete 47 files. Continue? [y/N]"
- Capitalize the default option in the bracket: `[Y/n]` means default yes, `[y/N]` means default no
- For high-risk operations, require typing the resource name instead of just "yes"

### Wizards (multi-step flows)
- Show a step counter: `[2/5] Select database engine:`
- Allow going back to the previous step (if feasible)
- Show a summary of all selections before final confirmation
- Write the result to a config file with a message: "Wrote config to ~/.config/tool/config.yaml"
- Support `--defaults` to skip the wizard and accept all defaults

### Selection and input types
- Single select: numbered list, user types number or name
- Multi-select: checkbox-style list with toggle (space to select, enter to confirm)
- Password/secret input: disable echo, show asterisks or nothing
- File path input: support tilde expansion and tab completion where possible

### Accessibility
- Never rely solely on color to convey selection state (use markers: `[x]`, `>`)
- Support screen readers by keeping output linear and avoiding cursor repositioning when possible
- Provide plain-text fallback for fancy TUI prompts via `--accessible` flag

## Gotchas / Anti-patterns
- Prompting when stdin is not a TTY -- hangs forever waiting for input
- Defaulting destructive confirmations to "yes" -- accidents in scripted environments
- Not providing a non-interactive path -- makes CI/CD usage impossible
- Using cursor positioning (ANSI codes) without checking terminal capabilities
- Mixing prompts into stdout -- breaks piping; prompts belong on stderr
- Wizard with no way to abort mid-flow -- user must Ctrl+C and lose all input

## References
- Command Line Interface Guidelines (interactivity): https://clig.dev/#interactivity
- isatty detection: POSIX `isatty(3)`, Go `term.IsTerminal()`, Java `System.console()`
- survey (Go interactive prompts): https://github.com/AlecAivazis/survey
- Inquirer.js (Node): https://github.com/SBoudrias/Inquirer.js

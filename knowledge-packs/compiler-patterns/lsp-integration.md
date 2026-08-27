# Language Server Protocol Integration

## When to use
- Building editor/IDE support for a programming language or DSL
- Implementing code completion, diagnostics, go-to-definition, or rename
- Adding language intelligence to a tool that already has a compiler frontend
- Supporting multiple editors (VS Code, Neovim, Emacs, Helix) with one implementation

## Pattern

### LSP Architecture
- The language server is a separate process that communicates with the editor via JSON-RPC over stdin/stdout
- The editor (client) sends notifications (`textDocument/didOpen`, `textDocument/didChange`) and requests (`textDocument/completion`)
- The server maintains its own model of the workspace and responds with results
- This decoupling means one server implementation serves every LSP-capable editor

### Core Capabilities

#### Diagnostics
- Publish diagnostics on file open and after every edit (debounced, typically 200-500ms)
- Use `textDocument/publishDiagnostics` to push error/warning/info/hint to the editor
- Include severity, source span (line/column range), message, and optional code
- Provide related information (secondary locations) for "expected X because of Y" errors
- Support `codeAction` quick-fixes linked to diagnostics

#### Completion
- Respond to `textDocument/completion` with a list of `CompletionItem`s
- Filter and sort server-side for performance; the client may further filter by user input
- Include: label, kind (function, variable, keyword), detail (type signature), documentation (markdown)
- Use `insertText` or `textEdit` to specify what gets inserted (may differ from the label)
- Lazy resolve: return minimal items in the list, fill in documentation on `completionItem/resolve`

#### Go to Definition / References
- `textDocument/definition`: return the location(s) where the symbol under the cursor is defined
- `textDocument/references`: return all locations where the symbol is used
- `textDocument/typeDefinition`: jump to the type of the symbol
- `textDocument/implementation`: jump to implementations of an interface/trait
- Support multi-root: definitions may be in different workspace folders or in dependencies

#### Hover
- Return a `MarkupContent` (markdown) with the symbol's type signature, documentation, and source location
- Keep it concise: type on the first line, doc summary below
- For expressions, show the inferred type even if there is no doc comment

#### Rename
- `textDocument/rename`: rename a symbol across the entire workspace
- `textDocument/prepareRename`: validate that the cursor is on a renameable symbol before showing the rename UI
- Return a `WorkspaceEdit` with all file changes — the editor applies them atomically
- Respect scope: renaming a local variable should not rename an unrelated variable with the same name in another function

### Incremental Analysis
- Re-analyze only what changed: use file-level or function-level granularity
- Salsa / query-based architecture: memoize analysis results, invalidate when inputs change
- Keep the full index in memory for fast lookups; persist to disk for fast cold starts
- Target response times: completion < 100ms, diagnostics < 500ms, go-to-definition < 50ms

### Workspace Management
- Track open files (with in-memory edits from the editor) vs closed files (read from disk)
- Watch for file system changes (`workspace/didChangeWatchedFiles`) to detect external edits
- Index the workspace on startup; show progress via `window/workDoneProgress`
- Support multi-root workspaces: the client may open multiple project folders

### Semantic Tokens
- `textDocument/semanticTokens`: provide rich token classification beyond what a TextMate grammar can express
- Token types: `function`, `variable`, `parameter`, `type`, `property`, `keyword`, `comment`, etc.
- Modifiers: `declaration`, `definition`, `readonly`, `static`, `deprecated`, `async`
- Enables themes to color variables differently from parameters, deprecated APIs with strikethrough, etc.

### Code Actions
- Respond to `textDocument/codeAction` with available refactorings and quick-fixes at the cursor/selection
- Link actions to diagnostics: "add missing import", "fix typo in identifier"
- Support `isPreferred` to indicate the most likely intended fix
- Categories: `quickfix`, `refactor`, `refactor.extract`, `refactor.inline`, `source.organizeImports`

## Gotchas / Anti-patterns
- **Blocking the main thread**: LSP requests arrive concurrently; a slow completion blocks diagnostics — use async or a thread pool
- **Full re-analysis on every keystroke**: kills responsiveness — debounce and use incremental analysis
- **Returning too many completion items**: editors struggle with 10,000 items — filter and limit server-side
- **Ignoring cancellation**: the client sends `$/cancelRequest` when the user types again — honor it to avoid wasted work
- **No progress reporting**: large workspace indexing with no feedback makes the user think the server is frozen
- **Hardcoding editor assumptions**: LSP is editor-agnostic; do not assume VS Code behavior

## References
- LSP Specification: https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/
- LSP overview: https://langserver.org/
- rust-analyzer architecture: https://github.com/rust-lang/rust-analyzer/blob/master/docs/dev/architecture.md
- Salsa incremental computation: https://salsa-rs.github.io/salsa/
- tower-lsp (Rust LSP framework): https://github.com/ebkalderon/tower-lsp
- lsp4j (Java LSP framework): https://github.com/eclipse-lsp4j/lsp4j

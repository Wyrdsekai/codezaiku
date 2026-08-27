# Compiler Error Messages

## When to use
- Designing error reporting for a compiler, interpreter, or linter
- Improving the developer experience of an existing language tool
- Building a diagnostic system that supports multiple output formats (terminal, IDE, JSON)
- Implementing "did you mean?" suggestions and fix-it hints

## Pattern

### Anatomy of a Good Error Message
1. **Location**: file path, line number, column number — always present
2. **Severity**: error (blocks compilation), warning (compiles but suspicious), note (additional context)
3. **Summary**: one sentence describing *what* is wrong, in the user's terms (not the compiler's internal jargon)
4. **Source snippet**: show the relevant line(s) of code with a pointer to the exact span
5. **Explanation**: *why* this is wrong, with context (e.g., "expected `Int` because function `add` returns `Int`")
6. **Suggestion**: *how* to fix it, ideally with a concrete code change

### Pointing to Source
- Store byte offset spans (start, end) on every AST node and IR instruction
- Underline the error span with `^~~~` or similar visual indicator
- For multi-line spans, show the full range with a vertical bar in the margin
- For "expected X because of Y" errors, show both locations: the error and the origin of the expectation

### Source Snippet Rendering
```
error[E0308]: mismatched types
 --> src/main.rs:4:18
  |
3 | fn add(a: i32, b: i32) -> i32 {
  |                            --- expected `i32` because of return type
4 |     return a + b + "hello";
  |                    ^^^^^^^ expected `i32`, found `&str`
```
- Show 1-3 lines of context around the error
- Use color: red for errors, yellow for warnings, blue for notes, green for suggestions
- Right-align line numbers and use a separator (`|`) for the margin

### Suggestion and Fix-It Hints
- Provide concrete replacement text when possible: "help: add `.to_string()` here"
- Show the suggested change as a diff within the snippet
- Machine-applicable suggestions can be auto-applied by the tool (`--fix` flag)
- Distinguish "confident suggestion" (auto-applicable) from "possible suggestion" (human review needed)

### Multiple Errors
- Continue after the first error — collect and report all diagnostics
- Limit to a configurable maximum (e.g., 20 errors) to avoid flooding the terminal
- Deduplicate: if the same error occurs on 50 lines, show the first few and summarize "... and 47 more"
- Order errors by source location (file, then line) for predictable reading order

### "Did You Mean?" Suggestions
- Use edit distance (Levenshtein) to find similar identifiers when a name is not found
- Search the current scope, imported modules, and common standard library names
- Only suggest if the distance is small relative to the name length (e.g., distance <= 2 for names < 8 chars)
- For unknown types, also suggest checking imports: "did you forget to import `HashMap`?"

### Error Codes and Documentation
- Assign a unique code to each error class (e.g., `E0308`, `TS2322`)
- Provide a `--explain E0308` command or web page with a detailed explanation and example
- Error codes allow users to search for solutions and allow tooling to filter/suppress specific errors

### Structured Output
- Support JSON or SARIF output for IDE integration and CI pipelines
- Include: file, start line/column, end line/column, severity, code, message, suggestions
- The LSP `Diagnostic` type is a good model for structured error output

## Gotchas / Anti-patterns
- **"Syntax error"**: a message with no location, no context, and no suggestion — completely unhelpful
- **Compiler jargon**: "cannot unify `TVar42` with `TConst(Int)`" — translate to user terms
- **Cascading errors**: one missing semicolon produces 30 errors — implement recovery to limit cascades
- **No color support detection**: blasting ANSI escape codes to a file redirect or a terminal that does not support them
- **Missing secondary locations**: "type mismatch" without showing where the expected type came from
- **Fix-it that makes it worse**: an auto-fix suggestion that introduces a new error — test suggestions

## References
- Rust compiler error index: https://doc.rust-lang.org/error_codes/
- Elm error message philosophy: https://elm-lang.org/news/compiler-errors-for-humans
- "Compiler Error Messages Considered Unhelpful" (Becker et al., 2019)
- SARIF specification: https://sarifweb.azurewebsites.net/
- Ariadne (Rust diagnostic library): https://github.com/zesterer/ariadne
- codespan-reporting: https://github.com/brendanzab/codespan

# Lexer Design

## When to use
- Building a compiler, interpreter, or language tooling from scratch
- Implementing a custom DSL with structured syntax
- Adding syntax highlighting or tokenization to an editor
- Parsing structured text formats that are too complex for regex alone

## Pattern

### Token Types
- Define an enum of all token kinds: keywords, identifiers, literals (int, float, string), operators, punctuation, whitespace, comments, EOF
- Distinguish single-character tokens (`;`, `(`, `+`) from multi-character tokens (`==`, `>=`, `->`)
- Keywords are identifiers that match a reserved set — lex as identifier first, then look up in a keyword table
- Every token carries: kind, lexeme (source text), span (file, start offset, end offset)

### Scanner Architecture
- **Single-pass, character-at-a-time**: maintain a current position and a start-of-token position
- Advance one character, dispatch on it to a handler for that token category
- For multi-character tokens (`==` vs `=`), peek ahead without consuming
- Produce a lazy stream or iterator of tokens — do not materialize the full list unless needed

### Lexer Modes
- Use modal lexing for languages with context-sensitive tokenization (string interpolation, heredocs, XML)
- Maintain a mode stack: push on entering a string template, pop on closing it
- Each mode has its own dispatch table — the interpolation mode lexes expressions, the string mode lexes literal text

### Unicode Handling
- Decide early: are identifiers ASCII-only or Unicode (UAX #31)?
- Use Unicode categories (`ID_Start`, `ID_Continue`) for identifier characters if supporting Unicode
- Normalize source to a canonical form (NFC) at input to avoid confusable-character bugs
- Handle BOM at file start; reject or warn on mixed line endings

### Whitespace and Comments
- Decide whether to emit whitespace/comment tokens or discard them
- Discard for compilation; emit for formatting tools, linters, and IDE features
- Attach comments to adjacent tokens as "trivia" if you need them for doc generation

### Error Recovery
- On an unexpected character, emit an `Error` token with the offending character(s) and continue scanning
- Never panic or abort on a single bad character — collect all errors so the user sees them at once
- For unterminated strings, scan to end-of-line or a likely closing delimiter, emit an error token, and resume
- Track error count; stop after a threshold to avoid cascading noise

### Performance Considerations
- Keyword lookup: perfect hash map or a trie — not a linear search through a list
- Avoid allocating strings for every token; store offsets into the original source buffer
- For very large files, memory-map the source and scan in-place
- Profile: lexing should be under 5% of total compilation time for most languages

## Gotchas / Anti-patterns
- **Regex-per-token**: compiling a regex for each token type is slow and hard to debug — hand-written scanners are faster and clearer
- **Losing source positions**: failing to track byte offsets makes error messages useless later
- **Greedy maximal munch failures**: `--x` lexed as `-- x` (decrement, identifier) vs `- -x` (negate, negate) — define and document the rule
- **Forgetting EOF**: the parser needs an explicit EOF token to know when input ends
- **Hardcoded ASCII**: rejecting valid Unicode identifiers or, worse, silently corrupting multi-byte characters
- **Stateless string lexing**: attempting to lex `"hello ${name} world"` without a mode stack

## References
- Crafting Interpreters, Chapter 4 "Scanning": https://craftinginterpreters.com/scanning.html
- Unicode Identifier Syntax (UAX #31): https://unicode.org/reports/tr31/
- re2c (lexer generator): https://re2c.org/
- Logos (Rust lexer generator): https://github.com/maciejhirsz/logos

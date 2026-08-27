# Parser Patterns

## When to use
- Building a parser for a programming language, DSL, or structured data format
- Choosing between parsing strategies (recursive descent, Pratt, PEG, parser generators)
- Designing an AST that is useful for both compilation and tooling
- Implementing parser error recovery for a good developer experience

## Pattern

### Recursive Descent
- One function per grammar rule: `parseExpression()`, `parseStatement()`, `parseBlock()`
- Each function consumes tokens and returns an AST node
- Entry point calls the top-level rule; recursion handles nesting
- Advantages: simple, debuggable, full control over error messages
- Limitation: left-recursive grammars need rewriting or a different technique for expressions

### Pratt Parsing (Top-Down Operator Precedence)
- Handles operator precedence and associativity elegantly without grammar rewriting
- Each token has a **null denotation** (nud: prefix behavior) and a **left denotation** (led: infix behavior)
- A binding power (integer) per operator determines precedence
- The core loop: parse a prefix, then repeatedly parse infix operators while binding power allows
- Right-associative operators use `bp - 1` for the right-hand side; left-associative use `bp`
- Ideal for expression-heavy languages; combine with recursive descent for statements

### AST Design
- Use a typed node hierarchy: `Expr` (subtypes: `BinaryExpr`, `CallExpr`, `LiteralExpr`) and `Stmt` (subtypes: `IfStmt`, `ReturnStmt`)
- Every node stores its source span (start and end positions) for error reporting and IDE features
- Distinguish **concrete syntax tree** (CST, preserves all tokens including whitespace) from **abstract syntax tree** (AST, semantic structure only)
- CST for formatters and linters; AST for compilation — or use a lossless CST that can serve both roles
- Make AST nodes immutable; transformations produce new trees

### Error Recovery Strategies
- **Synchronization**: on error, skip tokens until a known synchronization point (`;`, `}`, keyword) and resume parsing
- **Error productions**: add grammar rules that match common mistakes (missing semicolon, extra comma) and emit diagnostics
- **Insertion**: if a closing delimiter is missing, insert a synthetic one and report the error
- Collect all errors into a list — do not abort on the first one
- Mark AST nodes that were produced during error recovery so later phases can skip them

### Parser Generator vs Hand-Written
- **Hand-written**: full control, better error messages, easier incremental parsing; more code to maintain
- **Parser generators** (ANTLR, tree-sitter, Bison): faster to prototype, grammar-as-specification; harder to customize errors
- Tree-sitter: generates incremental parsers suitable for editors; outputs a CST
- For production compilers, the industry trend is hand-written recursive descent + Pratt (Go, Rust, TypeScript, Swift all use this)

### Incremental Parsing
- Re-parse only the changed region of the source, reusing unchanged subtrees
- Requires a tree structure that tracks byte ranges and can be spliced
- Tree-sitter provides this out of the box for editor integration
- For a custom parser: mark nodes with byte ranges, invalidate on edit, re-parse the affected span

## Gotchas / Anti-patterns
- **Left recursion in recursive descent**: `expr -> expr + term` causes infinite recursion — rewrite to iteration or use Pratt parsing
- **Ambiguous grammars**: if the grammar has multiple parse trees for one input, the parser silently picks one — formalize precedence
- **Error cascades**: one missing semicolon produces 50 errors because recovery re-synchronizes poorly
- **Losing source locations**: AST nodes without spans make "error at unknown location" — attach spans to every node
- **Monolithic parse function**: a 2000-line `parse()` function — factor into one function per grammar rule
- **Ignoring associativity**: `a - b - c` must parse as `(a - b) - c` (left-assoc), not `a - (b - c)`

## References
- Crafting Interpreters, Chapters 5-6: https://craftinginterpreters.com/parsing-expressions.html
- Pratt Parsing (Matklad): https://matklad.github.io/2020/04/13/simple-but-powerful-pratt-parsing.html
- Tree-sitter: https://tree-sitter.github.io/tree-sitter/
- ANTLR: https://www.antlr.org/
- "Simple but Powerful Pratt Parsing" (Bob Nystrom): https://journal.stuffwithstuff.com/2011/03/19/pratt-parsers-expression-parsing-made-easy/

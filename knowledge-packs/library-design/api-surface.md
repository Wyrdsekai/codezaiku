# Public API Surface Design

## When to use
- Designing a library or SDK intended for external consumption
- Reviewing an existing API for consistency before a major release
- Establishing API design guidelines for a team or organization
- Wrapping an internal module with a stable public contract

## Pattern

### Minimal Surface Area
- Export only what consumers need; everything else is internal
- Prefer a single entry point (barrel file, facade class, package-level exports)
- Each public symbol should have a clear reason to exist — if two methods always appear together, consider combining them
- Count your public symbols: fewer is almost always better

### Naming Conventions
- Use domain vocabulary consistently — pick one term and stick with it (e.g., "remove" vs "delete", not both)
- Method names should describe *what* happens, not *how* (e.g., `store(item)` not `insertIntoHashMap(item)`)
- Boolean-returning methods: use `is`, `has`, `can`, `should` prefixes
- Factory methods: `of`, `from`, `create` — pick one convention per library
- Avoid abbreviations unless universally understood in the domain

### Consistency Rules
- Same concept, same shape everywhere: if `getUser(id)` returns `Optional<User>`, then `getOrder(id)` returns `Optional<Order>`, not `Order | null`
- Parameter order should be predictable: context/target first, options last
- Overloads should be additive — each adds one optional concept
- Return types should be unsurprising: a "get" never throws, a "find" may return empty, a "require" throws

### Configuration and Defaults
- Provide sensible defaults for every option — zero-config should work
- Use the builder pattern or options objects for complex configuration
- Separate "what to do" from "how to do it" — keep policy out of mechanism

### Evolvability
- Prefer options objects over positional parameters (adding a field is non-breaking)
- Return concrete types sparingly; interfaces and abstract types preserve flexibility
- Reserve the right to add optional fields to configuration objects (document this contract)

## Gotchas / Anti-patterns
- **Kitchen sink API**: exposing internals "just in case" — every public symbol is a maintenance burden forever
- **Stringly typed interfaces**: using raw strings where enums or typed constants belong
- **Leaking implementation types**: returning internal data structures that you cannot change without breaking consumers
- **Inconsistent nullability**: some methods return null, others throw, others return Optional — pick one strategy
- **Positional boolean parameters**: `render(true, false)` is unreadable — use named options or enums
- **God objects**: one class with 40 methods — split by responsibility

## References
- Bloch, J. "How to Design a Good API and Why It Matters" (Google Tech Talk, 2007)
- Henning, M. "API Design Matters" (ACM Queue, 2007)
- Microsoft REST API Guidelines: https://github.com/microsoft/api-guidelines
- Java API Design Checklist: https://theamiableapi.com/

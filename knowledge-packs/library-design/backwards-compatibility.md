# Backwards Compatibility and Deprecation

## When to use
- Evolving a library API without breaking existing consumers
- Planning a deprecation cycle for outdated features
- Writing migration codemods or adapter layers
- Deciding whether a change warrants a MAJOR version bump

## Pattern

### Deprecation Process
1. **Announce**: mark the old API as deprecated with a clear message naming the replacement
2. **Document**: add a migration note to the changelog and the deprecated symbol's doc comment
3. **Warn**: emit a compile-time or runtime deprecation warning (not just a doc annotation)
4. **Maintain**: keep the deprecated API working for at least one MINOR release cycle (ideally two)
5. **Remove**: delete in the next MAJOR version, with a migration guide

### Deprecation Annotations
- Java: `@Deprecated(since="2.3", forRemoval=true)` + Javadoc `@deprecated Use {@link NewThing} instead`
- TypeScript/JavaScript: `/** @deprecated Use newMethod() instead */`
- Python: `warnings.warn("oldFunc is deprecated, use newFunc", DeprecationWarning, stacklevel=2)`
- Rust: `#[deprecated(since = "1.4.0", note = "Use new_function instead")]`
- Always name the replacement — "deprecated" alone is useless

### Adapter Patterns
- **Facade adapter**: new API internally, old API delegates to new API as a thin wrapper
- **Overload bridge**: add the new signature as an overload; old signature calls new one with defaults
- **Interface evolution**: add new methods with default implementations so existing implementors do not break
- **Configuration migration**: accept both old and new config keys; log a warning when old keys are detected

### Migration Codemods
- Write automated transforms that rewrite consumer code from old API to new API
- Scope codemods to syntactic changes (renames, argument reordering) — do not attempt semantic rewrites
- Provide codemods as a CLI tool: `npx @mylib/migrate v2-to-v3`
- Test the codemod against your own codebase and example repos before publishing
- Codemods are best-effort aids, not guarantees — document manual steps for cases they cannot handle

### What Counts as Breaking
- Removing or renaming a public symbol
- Changing a method's return type or parameter types
- Changing default behavior (even with the same signature)
- Narrowing accepted input or widening possible output
- Removing a configuration option or changing its semantics
- Bumping a dependency's minimum version if that dependency is exposed in your public API

### What Is Usually Safe
- Adding a new public method or class
- Adding an optional parameter with a default value (if using options objects)
- Widening accepted input (accepting a supertype where a subtype was required)
- Improving performance without changing observable behavior
- Adding new fields to output types (if consumers are not pattern-matching exhaustively)

### Compatibility Testing
- Keep a suite of tests written against the *previous* version's public API
- Run these "consumer-perspective" tests against the new version as part of CI
- If any fail, you have a breaking change — decide whether to fix it or bump MAJOR
- Consider running downstream open-source projects' test suites against your pre-release (ecosystem testing)

## Gotchas / Anti-patterns
- **Silent removal**: deleting a public API without a deprecation period — consumers discover the break at upgrade time
- **Deprecation without replacement**: marking something deprecated but providing no alternative
- **Permanent deprecation**: leaving deprecated APIs for years without ever removing them — they accumulate and confuse
- **Behavioral breaks disguised as patches**: changing semantics in a PATCH release because the signature did not change
- **Overly broad codemods**: a transform that makes incorrect changes in edge cases is worse than no codemod
- **Breaking transitive contracts**: your public type extends a dependency's type, and you upgrade that dependency to a new major

## References
- Java `@Deprecated` best practices: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Deprecated.html
- Rust API evolution RFC: https://rust-lang.github.io/rfcs/1105-api-evolution.html
- jscodeshift (JavaScript codemod toolkit): https://github.com/facebook/jscodeshift
- OpenRewrite (Java automated refactoring): https://docs.openrewrite.org/
- Rector (PHP automated refactoring): https://getrector.com/

# Library Documentation Patterns

## When to use
- Shipping a library or SDK that others will integrate
- Structuring documentation that scales from beginner to expert
- Setting up documentation-as-code workflows in CI
- Improving adoption of an existing library with poor docs

## Pattern

### Documentation Layers (progressive disclosure)
1. **README**: 30-second pitch, install command, one minimal example, link to full docs
2. **Getting Started**: 5-minute tutorial that produces a working result
3. **Guides**: task-oriented walkthroughs for common use cases (authentication, error handling, testing)
4. **API Reference**: exhaustive, auto-generated from source annotations
5. **Migration Guides**: version-to-version upgrade instructions with before/after code
6. **Architecture / Internals**: for contributors and advanced users (optional for small libraries)

### API Documentation
- Every public symbol gets a doc comment — no exceptions
- First sentence is a summary (tools extract it as a tooltip)
- Document parameters, return values, exceptions/errors, and thread safety
- Include a code example in the doc comment for any non-obvious method
- Document nullability and valid ranges explicitly

### Examples
- Provide runnable examples, not pseudocode — copy-paste should compile/execute
- Cover the 3-5 most common use cases as standalone example files
- Test your examples in CI (compile them, run them, assert output)
- Examples should use only public API — if an example needs internal access, the API has a gap

### Getting Started Guide
- Assume zero context — state the prerequisites (language version, OS, tools)
- One install command, one configuration step, one "hello world" invocation
- Show expected output at each step
- End with "next steps" links to guides

### Migration Guides
- One guide per MAJOR version bump
- Structure: what changed, why it changed, how to migrate (with code diffs)
- Provide codemods or migration scripts where feasible
- List breaking changes as a checklist consumers can work through

### Documentation-as-Code
- Store docs alongside source in the same repo — they version together
- Generate API reference from source annotations (Javadoc, rustdoc, typedoc, godoc)
- Lint docs in CI: check for broken links, missing doc comments, outdated examples
- Treat doc PRs with the same review rigor as code PRs

## Gotchas / Anti-patterns
- **README-only documentation**: dumping everything into one file that grows to 2000 lines
- **Stale examples**: examples that worked two versions ago but no longer compile
- **Auto-generated-only docs**: API reference without guides is a dictionary without a textbook
- **Internal jargon in public docs**: using your codebase's internal names instead of the concepts users understand
- **Missing error documentation**: documenting the happy path but not what happens when things fail
- **Copy-pasted docs across methods**: duplicated text drifts out of sync — use cross-references

## References
- Divio documentation system (tutorial, how-to, reference, explanation): https://docs.divio.com/documentation-system/
- Write the Docs community: https://www.writethedocs.org/
- Javadoc best practices: https://www.oracle.com/technical-resources/articles/java/javadoc-tool.html
- rustdoc book: https://doc.rust-lang.org/rustdoc/
- Docusaurus (docs-as-code framework): https://docusaurus.io/

# Testing Patterns for Libraries

## When to use
- Building a library that will be consumed by external projects
- Establishing a test strategy that covers correctness, compatibility, and contracts
- Introducing property-based or fuzz testing to a library codebase
- Setting up CI test matrices across language/runtime versions

## Pattern

### Test Layers

#### Unit Tests
- Test individual functions and classes in isolation
- Mock only external I/O boundaries (filesystem, network, clock) — not your own code
- Keep tests fast: the full unit suite should run in under 30 seconds
- Name tests by behavior, not method: `rejects_negative_amounts` not `testProcess`

#### Integration Tests
- Test the library's public API as a consumer would use it
- Use real implementations, not mocks — the point is to verify the assembled system
- Write one integration test per documented use case in your Getting Started / Guides
- These tests are your contract: if they break, you have a breaking change

#### Compatibility Tests
- Test against the minimum and maximum supported versions of your runtime/language
- Test against the minimum and maximum versions of declared dependencies
- Use CI matrices: `[java-17, java-21, java-25]`, `[node-18, node-20, node-22]`
- If you support multiple platforms (Linux, macOS, Windows), test on all of them

#### Property-Based Tests
- Define invariants that hold for *all* valid inputs, not just a few examples
- Useful for: serialization roundtrips, algebraic laws, parser/formatter pairs, sorting stability
- Start with `forAll(input) { assert serialize(deserialize(input)) == input }`
- Shrinking reveals the minimal failing case — always review shrunk output
- Combine with fuzzing for security-sensitive parsers

### Contract and Behavioral Tests
- Write tests that encode your documented behavioral guarantees
- If the docs say "thread-safe", write a concurrent stress test
- If the docs say "O(n)", write a test that asserts linear growth (not exact timing)
- Export a test kit if your library defines interfaces that others implement

### Test Fixtures and Helpers
- Provide test utilities as a separate module (e.g., `mylib-test`) for consumers who need them
- Include builders, fakes, and assertion helpers — not just the production code
- Version the test module alongside the main module

### Mutation Testing
- Measures whether tests detect injected faults (mutants)
- A high mutation score means tests are genuinely verifying behavior, not just exercising code paths
- Run periodically (not every CI push — it is slow) to find dead assertions

## Gotchas / Anti-patterns
- **Testing internals**: tests coupled to private implementation details break on every refactor
- **Example-only property tests**: writing property tests with hardcoded examples defeats the purpose — let the generator explore
- **Flaky time-dependent tests**: using `Thread.sleep` or wall-clock assertions — use controllable clocks
- **Missing edge cases**: empty collections, null/nil, max-int, Unicode, concurrent access — test boundaries explicitly
- **Test suite too slow to run locally**: developers skip it; split into fast (unit) and slow (integration) suites
- **No compatibility matrix**: "works on my machine" — consumers run different versions

## References
- Hypothesis (Python property-based testing): https://hypothesis.readthedocs.io/
- jqwik (Java property-based testing): https://jqwik.net/
- fast-check (TypeScript/JavaScript): https://fast-check.dev/
- Pitest (Java mutation testing): https://pitest.org/
- GitHub Actions matrix strategy: https://docs.github.com/en/actions/using-jobs/using-a-matrix-for-your-jobs

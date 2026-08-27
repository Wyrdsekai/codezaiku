# Dependency Management for Libraries

## When to use
- Designing a library that will be consumed as a dependency by other projects
- Auditing an existing library's dependency tree for bloat or risk
- Deciding whether to add a new dependency vs implement in-house
- Resolving version conflicts in a dependency graph

## Pattern

### Minimal Dependencies
- Every dependency you take is a dependency your consumers take — be frugal
- Before adding a dependency, ask: is the functionality worth the transitive cost?
- Prefer zero-dependency implementations for small utilities (parsing a date format, hashing a string)
- Measure the cost: dependency size, transitive tree depth, maintenance health, license compatibility

### Version Bounds
- Declare the *widest range that works*, not the *exact version you tested with*
- Lower bound: the minimum version that has the APIs you use
- Upper bound: avoid hard upper bounds unless you know a future version breaks you (they cause "dependency hell")
- Use compatible-with constraints (e.g., `^1.2.0`, `[1.2,2)`) to allow patches and minors
- Test against both the lower bound and the latest within your range in CI

### Peer Dependencies
- Use peer deps when your library wraps or extends another library and must share a single instance at runtime
- The consumer provides the version — your library declares compatibility range
- Document clearly: "requires Foo >= 2.0 as a peer dependency"
- Never put a peer dependency in your regular dependency list (it causes duplicate instances)

### Optional Dependencies
- Use optional/provided deps for features that only some consumers need
- Gate the code path: check at runtime whether the optional dependency is available
- Document which features require which optional deps
- Never fail at import/load time because an optional dep is missing — fail at the call site with a clear message

### Dependency Hygiene
- Pin exact versions in your lock file for reproducible builds, but publish with ranges
- Audit licenses: your library's effective license is the intersection of all dependency licenses
- Run `dependabot`, `renovate`, or equivalent — stale deps accumulate CVEs
- Prefer dependencies that themselves have few dependencies (shallow trees)

### Vendoring vs Depending
- Vendor when: the dep is tiny, unmaintained, or you need a surgical patch
- Depend when: the dep is actively maintained, security-critical (you want upstream fixes), or large
- If you vendor, document the source version and any modifications

## Gotchas / Anti-patterns
- **Dependency sprawl**: 5 lines of utility code pulled in via a 200-dependency package
- **Exact pinning in library metadata**: forces consumers into one version, causing conflicts
- **Phantom dependencies**: using a transitive dependency without declaring it — breaks when the intermediary drops it
- **License landmines**: a single GPL transitive dep can change your library's effective license
- **Diamond dependency conflicts**: two of your deps require incompatible versions of a shared dep — you must mediate or replace one
- **Ignoring deprecation warnings**: upstream deprecations become upstream removals in the next major

## References
- Requires.io / Libraries.io — dependency health dashboards
- SPDX License List: https://spdx.org/licenses/
- Maven version ranges: https://maven.apache.org/enforcer/enforcer-rules/versionRanges.html
- npm semver calculator: https://semver.npmjs.com/
- Gradle dependency constraints: https://docs.gradle.org/current/userguide/dependency_constraints.html

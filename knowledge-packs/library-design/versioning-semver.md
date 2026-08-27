# Semantic Versioning and Release Management

## When to use
- Publishing a library with external consumers who depend on version stability
- Automating changelog generation from commit history
- Deciding whether a change is breaking, feature, or patch
- Setting up CI/CD pipelines for library releases

## Pattern

### SemVer Core (MAJOR.MINOR.PATCH)
- **MAJOR**: incompatible API changes — removed public symbols, changed signatures, altered behavior contracts
- **MINOR**: backwards-compatible new functionality — new methods, new optional parameters, new types
- **PATCH**: backwards-compatible bug fixes — corrected behavior to match documented intent
- Version 0.x.y: no stability guarantees; MINOR may break. Use this honestly during early development

### Breaking Change Detection
- Compile the previous version's test suite against the new version — failures indicate breaks
- Maintain an API signature snapshot (e.g., `.api` files, API extractor output) and diff it in CI
- Behavioral breaks are harder: a method that now returns results in different order is breaking even if the signature is unchanged
- Transitive breaks count: if your public type exposes a dependency's type, upgrading that dependency can break your consumers

### Pre-release Versions
- Format: `1.0.0-alpha.1`, `1.0.0-beta.3`, `1.0.0-rc.1`
- Pre-release versions have lower precedence than the release: `1.0.0-alpha.1 < 1.0.0`
- Use alpha for incomplete features, beta for feature-complete but untested, rc for release candidates
- Each pre-release should be installable and testable — do not publish broken pre-releases

### Changelog Automation
- Conventional Commits (`feat:`, `fix:`, `BREAKING CHANGE:`) enable automated changelog generation
- Group entries by type: Breaking Changes (top, prominent), Features, Bug Fixes, Deprecations
- Include migration instructions inline for every breaking change
- Link to issues/PRs for each entry — the changelog is a trail of *why*

### Release Workflow
- Tag the release commit: `v1.2.3` (the `v` prefix is conventional)
- Never mutate a published version — if it is wrong, publish a new patch or yank it
- Automate: commit triggers CI, CI runs tests, passing tests trigger publish + tag + changelog
- Sign releases (GPG or Sigstore) for supply chain integrity

## Gotchas / Anti-patterns
- **SemVer theater**: claiming SemVer but bumping MAJOR for marketing reasons or PATCH for new features
- **0.x forever**: staying at 0.x to avoid commitment — if people depend on it, commit to 1.0
- **Invisible breaks**: changing default behavior without a MAJOR bump because the signature did not change
- **Changelog dumps**: auto-generated commit lists with no curation — the changelog should be human-readable
- **Yanking instead of patching**: removing a version leaves dependents broken; prefer publishing a corrective patch
- **Lockstep versioning in monorepos**: bumping all packages to the same version regardless of changes — version each package independently

## References
- Semantic Versioning Specification: https://semver.org/
- Conventional Commits: https://www.conventionalcommits.org/
- Keep a Changelog: https://keepachangelog.com/
- API Extractor (TypeScript): https://api-extractor.com/
- japicmp (Java API comparison): https://github.com/siom79/japicmp

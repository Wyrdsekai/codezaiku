# Library Packaging and Distribution

## When to use
- Preparing a library for publication to a package registry
- Supporting multiple module formats (CJS, ESM, types) from one source
- Optimizing library output for tree-shaking and bundle size
- Setting up automated publish pipelines

## Pattern

### Multi-Format Packaging
- Publish the format(s) your ecosystem expects:
  - **JVM**: JAR with sources JAR and Javadoc JAR; publish to Maven Central or internal Nexus
  - **JavaScript/TypeScript**: ESM as primary, CJS for legacy consumers, `.d.ts` type declarations
  - **Python**: wheel (`.whl`) as primary, sdist as fallback; publish to PyPI
  - **Rust**: source crate to crates.io (Cargo handles the rest)
  - **Go**: module source via VCS tag (no build artifact — `go get` fetches source)
- For JS: use `exports` field in `package.json` to map entry points per condition (`import`, `require`, `types`)

### Tree-Shaking Support
- Use ES module `export` (not `module.exports`) so bundlers can statically analyze usage
- Avoid side effects at module scope — or declare `"sideEffects": false` in `package.json`
- Do not re-export everything through a single barrel if it defeats dead-code elimination
- Mark pure function calls with `/*#__PURE__*/` annotations where needed

### Bundling Decisions
- Libraries should generally ship unbundled source (transpiled, not bundled) — let the consumer's bundler optimize
- Exception: if you target browsers directly (UMD/IIFE), provide a bundled build
- Do not bundle your dependencies into your output — declare them in metadata; let the consumer's resolver manage them
- Minification is the consumer's job for libraries; ship readable code with source maps

### Package Metadata
- `name`, `version`, `license`, `repository`, `description` — fill every field
- Declare `engines` / minimum runtime version
- Use `files` allowlist (not `.npmignore` blocklist) to control what ships — prevents accidental inclusion of tests, configs, secrets
- Include `CHANGELOG.md` and `LICENSE` in the published package

### Publish Pipeline
- Automate: tag push triggers CI, CI builds + tests + publishes
- Never publish from a developer's laptop — reproducibility requires CI
- Use provenance attestation where the registry supports it (npm provenance, Sigstore for Maven)
- Verify the published artifact: download it in a clean environment and run smoke tests
- Use `--dry-run` locally to inspect what will be published before the real push

### Monorepo Packaging
- Each publishable package gets its own metadata file and independent version
- Use workspace-level tooling to manage cross-package dependencies during development
- Publish order matters: dependencies before dependents; topological sort the publish sequence
- Shared build configuration (compiler options, lint rules) lives at the workspace root

## Gotchas / Anti-patterns
- **Publishing `node_modules` or build caches**: missing `files` allowlist leads to multi-megabyte packages
- **Bundling dependencies**: consumers get duplicate copies of shared deps, inflating their bundle
- **Transpiling too aggressively**: shipping ES5 when your minimum target is ES2020 bloats output with polyfills
- **Missing type declarations**: TypeScript consumers get no intellisense and may avoid your library
- **Non-reproducible builds**: publishing from a dirty worktree with uncommitted changes
- **Forgetting platform-specific builds**: native addons need prebuilt binaries per OS/arch or a build step

## References
- npm `exports` field: https://nodejs.org/api/packages.html#package-entry-points
- Maven Central publishing requirements: https://central.sonatype.org/publish/requirements/
- PyPI packaging guide: https://packaging.python.org/en/latest/
- Rust crate publishing: https://doc.rust-lang.org/cargo/reference/publishing.html
- Gradle Maven Publish plugin: https://docs.gradle.org/current/userguide/publishing_maven.html

# Supply Chain Security

## When to use
- Managing third-party dependencies in any software project
- Building CI/CD pipelines that need to verify artifact integrity
- Complying with software supply chain security requirements (EO 14028, EU CRA)
- Defending against dependency confusion, typosquatting, and compromised packages

## Pattern

### Dependency Pinning
- Pin every dependency to an exact version (not ranges): `lodash@4.17.21`, not `lodash@^4.17.0`
- Use lockfiles: `package-lock.json`, `go.sum`, `Cargo.lock`, `gradle.lockfile`, `poetry.lock`
- Commit lockfiles to version control; they are part of the reproducible build definition
- Hash verification: lockfiles should include integrity hashes (SHA-256/SHA-512); verify on install
- Automated updates: use tools (Dependabot, Renovate) to propose version bumps as PRs with changelog diffs

### Lockfile Hygiene
- Regenerate lockfiles in CI to detect drift between lockfile and manifest
- Audit lockfile changes in PRs: unexpected dependency additions or version changes may indicate compromise
- Vendor dependencies (copy into repo) for high-security environments; eliminates runtime registry dependency
- Mirror registries: run a private registry mirror/proxy to cache and control available packages

### Software Bill of Materials (SBOM)
- Generate SBOM in standard format: SPDX or CycloneDX
- Include: direct and transitive dependencies, versions, licenses, package URLs (purl)
- Generate at build time (not after the fact) for accuracy; integrate into CI pipeline
- Store SBOMs alongside release artifacts; provide to customers and auditors
- Use SBOMs for vulnerability matching: feed into vulnerability databases for continuous monitoring

### Sigstore Verification
- **Cosign**: sign and verify container images and artifacts using keyless signing (OIDC identity)
- **Rekor**: transparency log recording signing events; provides tamper-evident audit trail
- **Fulcio**: issues short-lived certificates tied to OIDC identity; no long-lived signing keys to manage
- Verify signatures in CI before deployment: `cosign verify --certificate-identity=... --certificate-oidc-issuer=...`
- Sign your own artifacts: build artifacts, container images, SBOMs; allow consumers to verify provenance

### Build Provenance
- SLSA (Supply-chain Levels for Software Artifacts) framework: levels 1-4 of build integrity
- SLSA Level 1: documentation of build process; Level 2: hosted, authenticated build; Level 3: hardened build platform
- Generate provenance attestations: who built it, from what source, using what builder, with what inputs
- in-toto attestations: link metadata describing each step in the build pipeline
- Verify provenance before deployment: reject artifacts without valid provenance attestations

### Registry Security
- Enable 2FA/MFA on all package registry accounts (npm, PyPI, crates.io, Maven Central)
- Use scoped/namespaced packages to reduce typosquatting risk (@myorg/package-name)
- Dependency confusion defense: configure package managers to prefer internal registry for internal package names
- Pre-publish review: for internal packages, require approval before publishing new versions
- Monitor for typosquats: watch for packages with names similar to your dependencies

## Gotchas / Anti-patterns
- **No lockfile committed**: builds are non-reproducible; different installs may get different (possibly compromised) versions
- **Trusting transitive dependencies blindly**: your app has 50 direct deps but 500 transitive deps; any can be compromised
- **Install scripts with network access**: `postinstall` scripts can download and execute arbitrary code; audit or disable
- **SBOM as checkbox**: generating an SBOM but never using it for vulnerability tracking or incident response
- **Registry credentials in CI logs**: package manager auth tokens leaked in build output
- **No namespace squatting defense**: internal package names not registered on public registries; vulnerable to dependency confusion

## References
- SLSA framework — https://slsa.dev/
- Sigstore project — https://sigstore.dev/ (Cosign, Rekor, Fulcio)
- SPDX specification — https://spdx.dev/
- CycloneDX specification — https://cyclonedx.org/
- CISA "Securing the Software Supply Chain" guidance — government recommendations
- OpenSSF Scorecard — automated security health checks for open source projects

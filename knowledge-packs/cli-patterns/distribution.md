# Distribution

## When to use
- CLI tool is ready for users beyond the development team
- Tool must be installable across Linux, macOS, and Windows
- Users expect a single command or download to install
- Reproducible builds and supply chain integrity matter

## Pattern

### Single binary distribution
- Compile to a statically linked binary with no runtime dependencies
- Cross-compile for target triples: `linux-amd64`, `linux-arm64`, `darwin-amd64`, `darwin-arm64`, `windows-amd64`
- Name binaries consistently: `tool-<os>-<arch>` or `tool_<version>_<os>_<arch>`
- Provide checksums (SHA-256) and signatures (GPG or Sigstore/cosign) alongside binaries
- Host binaries on GitHub Releases, a CDN, or an object store

### Package managers
- **Homebrew** (macOS/Linux): maintain a formula or tap; use `brew audit` to validate
- **APT/DEB** (Debian/Ubuntu): build `.deb` packages with correct control file, dependencies, man pages
- **RPM** (Fedora/RHEL): build `.rpm` with spec file; publish to COPR or custom repo
- **AUR** (Arch): provide a `PKGBUILD`; community can maintain the AUR package
- **Scoop/Chocolatey** (Windows): maintain a manifest; Scoop is simpler for dev tools
- **Nix**: provide a `flake.nix` or submit to nixpkgs

### Homebrew specifics
- Formula lives in a tap repo: `homebrew-tap/Formula/tool.rb`
- Use `url` pointing to a versioned tarball with `sha256`
- Include a `test` block that runs `tool --version`
- Automate formula updates via CI on release

### Install scripts
- Provide a `curl | sh` install script for quick onboarding
- Script should detect OS and architecture, download the correct binary, verify checksum
- Install to a user-writable location by default (`~/.local/bin`, not `/usr/local/bin`)
- Never require `sudo` by default; document it as an option for system-wide install
- Pin to a specific version by default; support `--latest`

### Container images
- Publish a minimal container image (distroless or scratch-based) for CI/CD use
- Tag with version and `latest`; use immutable digests in documentation
- Include only the binary and essential certificates/timezone data

### Versioning and updates
- Follow semantic versioning strictly
- Embed version, commit hash, and build date in the binary (`tool --version` shows all three)
- Provide an `update` or `self-update` subcommand that downloads and replaces the binary
- Self-update should verify checksums before replacing the running binary

### Reproducible builds
- Pin all build dependencies (toolchain version, library versions)
- Document the build process so users can verify from source
- Use build flags to strip paths and timestamps for deterministic output

## Gotchas / Anti-patterns
- Requiring a runtime (Python, Node, JVM) for a CLI that could be a static binary
- Shipping unsigned binaries -- users and package managers increasingly require signatures
- `curl | sudo sh` as the primary install method -- security risk, poor practice
- Not including a `--version` flag or embedding version at build time
- Forgetting `arm64` builds -- Apple Silicon and ARM servers are now mainstream
- Self-update that requires root privileges when the original install did not

## References
- GoReleaser: https://goreleaser.com/
- Homebrew Formula Cookbook: https://docs.brew.sh/Formula-Cookbook
- Sigstore/cosign: https://docs.sigstore.dev/
- Semantic Versioning: https://semver.org/
- FPM (package builder): https://fpm.readthedocs.io/

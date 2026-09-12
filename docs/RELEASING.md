# Releasing CodeZaiku

How a release is built, checked and published, and what the release workflow does after that. The
short form: the bytes people install are built and tried on real machines; CI signs what was
published and builds nothing.

## What a release carries

| asset | made by |
|---|---|
| `codezaiku-X.Y.Z.tar.gz` | `scripts/release-build.sh` — the program for Linux, macOS and Windows, Java 21 or newer needed |
| `codezaiku-X.Y.Z-<platform>.tar.gz` for linux-x64, linux-arm64, macos-x64, macos-arm64, windows-x64 | `scripts/package-runtime.sh`, run by the release build — the same program with its own Java runtime (Temurin 21, trimmed by jlink to the modules the jars use), nothing to install first; from 0.3.2 |
| `codezaiku_X.Y.Z_all.deb` | `packaging/deb/build-deb.sh`, run by the release build — Debian and Ubuntu; declares a JRE dependency |
| `SHA256SUMS` | the release build — every tarball and the .deb, bare file names |
| `<asset>.sigstore.json` | the release workflow, after publication |
| `@wyrdsekai/codezaiku-mcp@X.Y.Z` on npm | the release workflow, from `npm/` |
| `io.github.Wyrdsekai/codezaiku` in the MCP Registry | the release workflow, from `server.json` |

The version lives in `build.gradle.kts`; `npm/package.json`, `server.json` and `FamiliarMain.VERSION`
must name the same one, and `release-build.sh` refuses to build when they do not. The workflow
checks them against the tag again.

## Build and check

```
scripts/release-build.sh                         # dist/: the tarball, the five runtime builds, the .deb, SHA256SUMS
scripts/verify-platform.sh                       # an installed build against a model server, on each box
cd npm && LAUNCHER_TEST_DIST=../dist npm test    # the npm launcher against the built release
```

Install the tarball on a machine that is not the build machine and run `codezaiku --version` and
`codezaiku doctor` from it; install the `.deb` the same way, since a package that builds is not a
package that installs. Try a runtime build on a machine with no Java (a `debian:bookworm-slim`
container will do). The runtime's binaries carry Adoptium's Developer ID signature and notarization,
and jlink copies them unchanged, so no signing of ours is involved. The tests are
`./gradlew :core:test`, the same command CI runs.

## Publish

Create the GitHub release with every file in `dist/` attached. That fires
`.github/workflows/release.yml`, which downloads each asset, checks it against the release's own
`SHA256SUMS`, attests it with Sigstore, and uploads the bundle beside it. Then `launcher` publishes
the npm package and `registry` publishes `server.json` to the MCP Registry. Released artifacts are immutable: a bad one is a new
version, never a re-upload.

Verify from any machine with the GitHub CLI:

```
gh attestation verify codezaiku-X.Y.Z.tar.gz --repo Wyrdsekai/codezaiku \
  --predicate-type https://codezaiku.org/attestation/release/v1
```

## Set up once, by hand

The workflow publishes to npm and to the registry without tokens, through GitHub's OIDC identity.
Both need a first step by a person:

1. **npm.** The `@wyrdsekai` scope belongs to the maintainer's npm account. The first version of
   the launcher is published by hand, after the GitHub release exists, because npm's trusted
   publishing is configured on a package that already exists:
   ```
   cd npm && npm login && npm publish --access public
   ```
   Then, on npmjs.com, the package's settings: Trusted publisher → GitHub Actions, organization
   `Wyrdsekai`, repository `codezaiku`, workflow `release.yml`. From the next release the
   workflow publishes; a version already on npm is left alone.
2. **MCP Registry.** `io.github.Wyrdsekai/*` is granted to an owner of the GitHub organization.
   The first publish is by hand from the repository root: `mcp-publisher login github`, then
   `mcp-publisher publish`. In the workflow, `mcp-publisher login github-oidc` needs nothing
   further. The registry is in preview and may reset; a reset means publishing again from the tag.

## The site

`codezaiku.org` serves `scripts/install-remote.sh` as `/install` and `scripts/install.ps1` as
`/install.ps1`, copied at deploy time. Neither pins a version — both resolve the latest release
when run — so a release does not need them edited. The download page lists every asset with its
checksum.

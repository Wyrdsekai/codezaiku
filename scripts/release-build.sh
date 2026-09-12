#!/usr/bin/env bash
# Build every release artifact and its checksums, from this tree, on this machine.
#
#   scripts/release-build.sh   -> dist/codezaiku-<version>.tar.gz, the five builds with their own Java runtime,
#                                 dist/codezaiku_<version>_all.deb, dist/SHA256SUMS
#
# The version is the one in build.gradle.kts, and the npm launcher (npm/package.json) and the registry entry
# (server.json) must name the same one: they move together, and the release workflow checks them against the tag.
# CI signs what is published; it builds nothing (see .github/workflows/release.yml and docs/RELEASING.md).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
VER="$(sed -n 's/^ *version = "\(.*\)"/\1/p' build.gradle.kts | head -1)"
[[ -n "$VER" ]] || { echo "no version in build.gradle.kts" >&2; exit 1; }
[[ "$VER" != *-SNAPSHOT ]] || { echo "version $VER is a snapshot; a release is not" >&2; exit 1; }
for f in npm/package.json server.json; do
    V=$(python3 -c "import json; print(json.load(open('$f'))['version'])")
    [[ "${V%%-*}" == "$VER" ]] || { echo "$f says $V, build.gradle.kts says $VER — bump them together" >&2; exit 1; }   # an npm-only suffix (0.1.6-1) is allowed
done
SRC_V=$(grep -o 'VERSION = "[0-9.]*"' core/src/main/java/org/codezaiku/FamiliarMain.java | grep -o '[0-9.]*')
[[ "$SRC_V" == "$VER" ]] || { echo "FamiliarMain.VERSION is $SRC_V, build.gradle.kts says $VER — bump them together" >&2; exit 1; }
./gradlew -q :core:installDist
# every model row and pinned build the on-demand install would fetch must still resolve (a row went 404 upstream once, 2026-09-12)
core/build/install/codezaiku/bin/codezaiku model serve check
rm -rf dist && mkdir -p dist
tar czf "dist/codezaiku-$VER.tar.gz" -C core/build/install codezaiku
# the builds with their own Java runtime, one per platform, from the tarball just made (scripts/package-runtime.sh)
scripts/package-runtime.sh dist "$VER"
CODEZAIKU_VERSION="$VER" bash packaging/deb/build-deb.sh
cp "build/deb/codezaiku_${VER}_all.deb" dist/
( cd dist && sha256sum codezaiku-*.tar.gz codezaiku_*_all.deb > SHA256SUMS )   # bare names: the workflow looks each asset up by name
ls -l dist
echo "publish: gh release create v$VER dist/codezaiku-*.tar.gz dist/codezaiku_${VER}_all.deb dist/SHA256SUMS --repo Wyrdsekai/codezaiku --title v$VER --notes-file <notes>"

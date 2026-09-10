#!/usr/bin/env bash
# Build a .deb for CodeZaiku.
#
#   packaging/deb/build-deb.sh                    # version from VERSION file
#   CODEZAIKU_VERSION=0.3.0 packaging/deb/build-deb.sh
#
# Installs to /opt/codezaiku with a /usr/bin/codezaiku symlink. Architecture is `all`:
# this is a JVM application, so the same package works on amd64 and arm64.
#
# Requires: dpkg-deb (apt install dpkg-dev), a JDK 21+ to build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
VERSION="${CODEZAIKU_VERSION:-$(cat "$REPO_ROOT/VERSION" 2>/dev/null || echo 0.1.1)}"
# The version lives in three places (VERSION, build.gradle.kts, FamiliarMain.VERSION) and the
# 0.2.0 build shipped a deb stamped 0.1.1 because only one had been bumped. Refuse the mismatch.
SRC_V=$(grep -o 'VERSION = "[0-9.]*"' "$REPO_ROOT/core/src/main/java/org/codezaiku/FamiliarMain.java" | grep -o '[0-9.]*')
if [ -n "$SRC_V" ] && [ "$SRC_V" != "$VERSION" ]; then
  echo "[deb] REFUSING: packaging version $VERSION != FamiliarMain.VERSION $SRC_V" >&2
  exit 1
fi
PKG="codezaiku_${VERSION}_all"
DEB_ROOT="$REPO_ROOT/build/deb/$PKG"
OUT_DIR="$REPO_ROOT/build/deb"

say() { printf '[deb] %s\n' "$*"; }
die() { printf '[deb] %s\n' "$*" >&2; exit 1; }

command -v dpkg-deb >/dev/null 2>&1 || die "dpkg-deb not found — apt install dpkg-dev"

say "building distribution"
cd "$REPO_ROOT"
./gradlew -q :core:installDist --console=plain || die "gradle build failed"
DIST="$REPO_ROOT/core/build/install/codezaiku"
[[ -x "$DIST/bin/codezaiku" ]] || die "no launcher at $DIST/bin/codezaiku"

say "staging $PKG"
rm -rf "$DEB_ROOT"
mkdir -p "$DEB_ROOT/DEBIAN" \
         "$DEB_ROOT/opt/codezaiku" \
         "$DEB_ROOT/usr/bin" \
         "$DEB_ROOT/usr/share/doc/codezaiku" \
         "$DEB_ROOT/usr/lib/systemd/system"

cp -r "$DIST/." "$DEB_ROOT/opt/codezaiku/"
ln -sf /opt/codezaiku/bin/codezaiku "$DEB_ROOT/usr/bin/codezaiku"

# Look in BOTH layouts. These files live under docs/public/ in the private tree and at the ROOT of
# the published one, because the export promotes the landing-page docs — so a path that is correct
# here is wrong in the repository people actually clone. Measured: the deb build died with
# `cannot stat docs/public/LICENSE` on the exported tree, which is the only tree an OSS user has.
pick() { for c in "$@"; do [[ -f "$c" ]] && { printf '%s' "$c"; return 0; }; done; return 1; }
LICENSE_SRC="$(pick "$REPO_ROOT/LICENSE" "$REPO_ROOT/docs/public/LICENSE")" \
  || die "LICENSE not found at the repo root or under docs/public/"
cp "$LICENSE_SRC" "$DEB_ROOT/usr/share/doc/codezaiku/copyright"
if README_SRC="$(pick "$REPO_ROOT/README.md" "$REPO_ROOT/docs/public/README.md")"; then
  cp "$README_SRC" "$DEB_ROOT/usr/share/doc/codezaiku/"
fi

INSTALLED_KB=$(du -sk "$DEB_ROOT/opt" | cut -f1)

cat > "$DEB_ROOT/DEBIAN/control" << EOF
Package: codezaiku
Version: $VERSION
Section: devel
Priority: optional
Architecture: all
Depends: default-jre-headless | openjdk-21-jre-headless | java21-runtime-headless
Recommends: git, docker.io
Suggests: trivy
Installed-Size: $INSTALLED_KB
Maintainer: CodeZaiku contributors <noreply@codezaiku.invalid>
Homepage: https://github.com/Wyrdsekai/codezaiku
Description: Autonomous harness for small local language models
 CodeZaiku drives a small local model through work that normally assumes a
 frontier model: writing and maintaining code, running ML pipelines, operating
 a service stack, and researching questions against live sources.
 .
 It ships no model weights. Point CODEZAIKU_DRIVE at any OpenAI-compatible
 inference server and run "codezaiku doctor" to check the environment.
 .
 The operator can change live systems, so it defaults to the "propose" authority
 rung: it tells you the command rather than running it. Acting rungs are guarded
 by blast-radius limits, snapshot-and-rollback, and a harm check.
EOF

# A package must not silently start an agent that can modify the machine. The unit
# ships DISABLED; the admin enables it after choosing an authority ceiling.
cat > "$DEB_ROOT/usr/lib/systemd/system/codezaiku.service" << 'EOF'
[Unit]
Description=CodeZaiku operations dispatch server
Documentation=file:///usr/share/doc/codezaiku/README.md
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
# Point this at your model server before enabling the unit.
Environment=CODEZAIKU_DRIVE=http://localhost:8200
Environment=CODEZAIKU_OPS_AUDIT=/var/lib/codezaiku/audit.jsonl
# Conservative by default: propose, never act. Raise deliberately.
Environment=CODEZAIKU_OPS_AUTHORITY=propose
ExecStart=/opt/codezaiku/bin/codezaiku serve 7070
Restart=on-failure
RestartSec=5
StateDirectory=codezaiku
# The operator inspects and repairs services, so it is not sandboxed away from
# them — but it has no business writing outside its own state directory.
ProtectHome=read-only
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF

cat > "$DEB_ROOT/DEBIAN/postinst" << 'EOF'
#!/bin/sh
set -e
mkdir -p /var/lib/codezaiku
# An UPGRADE ($2 is the old version) swaps the jars under any running service. A JVM loads classes
# lazily, so a process started against the old jars can fail on the first class it has not touched
# yet, minutes later and far from the cause. We do NOT restart it: this operator can change live
# systems, and taking one down mid-remediation to install a patch release is exactly the kind of
# unasked-for action the rest of the design refuses. Say it plainly and let the admin choose.
if [ "$1" = configure ] && [ -n "$2" ] && systemctl is-active --quiet codezaiku.service 2>/dev/null; then
  cat <<'MSG'

codezaiku.service is RUNNING and still has the previous version's jars open.
Restart it when it is safe to do so:

  sudo systemctl restart codezaiku

MSG
fi
if [ "$1" = configure ] && [ -z "$2" ]; then
  cat <<'MSG'

CodeZaiku installed.

  codezaiku doctor    check the environment (it names what is missing and the fix)
  codezaiku help      all commands

It needs an OpenAI-compatible model server; no weights are bundled:
  export CODEZAIKU_DRIVE=http://localhost:8200

A systemd unit is installed but NOT enabled — CodeZaiku can modify live systems,
so starting it is a deliberate act. To run the dispatch server:
  sudoedit /usr/lib/systemd/system/codezaiku.service   # set CODEZAIKU_DRIVE
  sudo systemctl enable --now codezaiku

MSG
fi
exit 0
EOF

cat > "$DEB_ROOT/DEBIAN/prerm" << 'EOF'
#!/bin/sh
set -e
if [ "$1" = remove ]; then
  systemctl stop codezaiku.service 2>/dev/null || true
  systemctl disable codezaiku.service 2>/dev/null || true
fi
exit 0
EOF

# /var/lib/codezaiku holds learned cards and the audit trail — user data. Removed only
# on purge, never on a plain remove or an upgrade.
cat > "$DEB_ROOT/DEBIAN/postrm" << 'EOF'
#!/bin/sh
set -e
if [ "$1" = purge ]; then
  rm -rf /var/lib/codezaiku
fi
exit 0
EOF

chmod 0755 "$DEB_ROOT/DEBIAN/postinst" "$DEB_ROOT/DEBIAN/prerm" "$DEB_ROOT/DEBIAN/postrm"

say "packing"
dpkg-deb --build --root-owner-group "$DEB_ROOT" "$OUT_DIR/$PKG.deb" >/dev/null

say "built $OUT_DIR/$PKG.deb  ($(du -h "$OUT_DIR/$PKG.deb" | cut -f1))"
say "install:  sudo apt install $OUT_DIR/$PKG.deb"

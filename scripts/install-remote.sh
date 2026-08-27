#!/bin/sh
# CodeZaiku one-line installer.
#
#   curl -fsSL https://codezaiku.org/install | sh
#
# Downloads the release tarball from GitHub, VERIFIES it against the release's own SHA256SUMS, and
# installs it. Only this script comes from wherever you fetched it — the artifact and the checksums
# both come from the same GitHub release, so this script cannot hand you a payload those checksums
# do not match. If you would rather read before running, that is the right instinct: fetch it,
# read it, then run it.
#
#   CODEZAIKU_VERSION=0.1.0   install a specific release instead of the latest
#   CODEZAIKU_PREFIX=~/.local where to install (default ~/.local, or /usr/local when run as root)
set -eu

REPO="Wyrdsekai/codezaiku"
BASE="${CODEZAIKU_DOWNLOAD_BASE:-}"          # test hook; empty means GitHub
PREFIX="${CODEZAIKU_PREFIX:-}"
[ -n "$PREFIX" ] || { [ "$(id -u)" = "0" ] && PREFIX=/usr/local || PREFIX="$HOME/.local"; }

die() { printf 'codezaiku: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# A JRE is the one thing not bundled. Say so before downloading 23MB someone cannot run — and on a
# machine with apt, point at the .deb rather than leaving someone to go and find a JDK: it declares a
# JRE dependency, so apt installs one. Otherwise the path we RECOMMEND is the one that stops dead on
# a bare Debian box while a path we mention in passing would have worked.
if ! have java; then
    if have apt; then
        die "java not found — CodeZaiku needs a JRE or JDK 21 or newer.
  On Debian or Ubuntu the .deb is easier: it declares a JRE dependency, so apt installs one for you.
      https://github.com/$REPO/releases/latest  ->  codezaiku_<version>_all.deb
      sudo apt install ./codezaiku_<version>_all.deb
  Or install a JRE first (sudo apt install default-jre-headless) and re-run this."
    fi
    die "java not found — CodeZaiku needs a JRE or JDK 21 or newer on PATH"
fi
JV=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')
[ "${JV:-0}" -ge 21 ] 2>/dev/null || die "java $JV found — CodeZaiku needs 21 or newer"
have curl || die "curl not found"
have tar  || die "tar not found"

VER="${CODEZAIKU_VERSION:-}"
if [ -z "$VER" ] && [ -z "$BASE" ]; then
    VER=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
          | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -1)
    [ -n "$VER" ] || die "could not determine the latest release — set CODEZAIKU_VERSION"
fi
[ -n "$BASE" ] || BASE="https://github.com/$REPO/releases/download/v$VER"

TAR="codezaiku-$VER.tar.gz"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

printf 'codezaiku: downloading %s\n' "$TAR"
curl -fsSL "$BASE/$TAR" -o "$TMP/$TAR" || die "download failed: $BASE/$TAR"
curl -fsSL "$BASE/SHA256SUMS" -o "$TMP/SHA256SUMS" || die "no SHA256SUMS in the release — refusing to install unverified"

# Verify against the release's own checksums. An artifact that does not match is not installed:
# a partial download and a substituted one look identical until you check.
EXPECT=$(sed -n "s/^\([0-9a-f]\{64\}\)  *\.\{0,1\}\/\{0,1\}$TAR\$/\1/p" "$TMP/SHA256SUMS" | head -1)
[ -n "$EXPECT" ] || die "$TAR is not listed in SHA256SUMS"
if have sha256sum; then ACTUAL=$(sha256sum "$TMP/$TAR" | cut -d' ' -f1)
elif have shasum;   then ACTUAL=$(shasum -a 256 "$TMP/$TAR" | cut -d' ' -f1)
else die "no sha256sum or shasum — cannot verify the download"; fi
[ "$EXPECT" = "$ACTUAL" ] || die "checksum mismatch for $TAR — refusing to install
  expected $EXPECT
  got      $ACTUAL"
printf 'codezaiku: checksum verified\n'

LIBDIR="$PREFIX/share/codezaiku"
BINLINK="$PREFIX/bin/codezaiku"
mkdir -p "$TMP/x" "$PREFIX/bin" "$(dirname "$LIBDIR")"
tar xzf "$TMP/$TAR" -C "$TMP/x"
rm -rf "$LIBDIR"
mv "$TMP/x/codezaiku" "$LIBDIR"

# A wrapper, never a symlink: MSYS on Windows copies instead of linking, and the launcher derives
# its own location from $0 — a copy resolves to the wrong directory and fails at runtime.
printf '#!/bin/sh\nexec "%s/bin/codezaiku" "$@"\n' "$LIBDIR" > "$BINLINK"
chmod +x "$BINLINK"

printf 'codezaiku: installed %s\n' "$BINLINK"
case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) printf 'codezaiku: %s is not on your PATH — add it:\n      export PATH="%s/bin:$PATH"\n' \
              "$PREFIX/bin" "$PREFIX" ;;
esac
printf '\nnext:  codezaiku doctor      # names anything missing, and the fix\n'

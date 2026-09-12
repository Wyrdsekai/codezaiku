#!/usr/bin/env bash
# The builds that carry their own Java: the release tarball plus a trimmed Java 21 runtime for each platform,
# so `npx @wyrdsekai/codezaiku-mcp`, the install one-liners and a plain download work on a machine with no JDK.
#
#   scripts/package-runtime.sh dist 0.1.6                 # every platform
#   scripts/package-runtime.sh dist 0.1.6 linux-x64       # one
#
# In:  dist/codezaiku-<version>.tar.gz (scripts/release-build.sh makes it; a release asset is the same bytes)
# Out: dist/codezaiku-<version>-<platform>.tar.gz for linux-x64, linux-arm64, macos-x64, macos-arm64, windows-x64:
#      the same codezaiku/ folder with a jre/ beside bin/ and lib/, and the start scripts pointed at it.
#
# jlink builds a runtime for ANY platform from that platform's JDK modules, so one machine makes all five. The JDKs
# are Temurin 21 (the floor this program is compiled for), fetched once into $WYRDSEKAI_JDK_CACHE
# (default ~/.cache/wyrdsekai-jdks, about 190 MB each). The module list comes from jdeps on the jars, plus the
# modules a runtime needs that no jar names: character sets and locales for pages in other languages, the
# elliptic-curve TLS provider, sun.misc for Lucene, the zip file system, management for the JVM's own use.
set -euo pipefail
TOOL=codezaiku
DIST="${1:?dist dir}"; VER="${2:?version}"; shift 2
TARGETS=("$@"); [ ${#TARGETS[@]} -gt 0 ] || TARGETS=(linux-x64 linux-arm64 macos-x64 macos-arm64 windows-x64)
JDK_MAJOR=21
CACHE="${WYRDSEKAI_JDK_CACHE:-$HOME/.cache/wyrdsekai-jdks}"
SRC="$DIST/$TOOL-$VER.tar.gz"
[ -f "$SRC" ] || { echo "no $SRC (run scripts/release-build.sh first)" >&2; exit 1; }
mkdir -p "$CACHE"

adoptium() { case "$1" in
    linux-x64) echo linux/x64;; linux-arm64) echo linux/aarch64;; macos-x64) echo mac/x64;; macos-arm64) echo mac/aarch64;; windows-x64) echo windows/x64;;
    *) echo "unknown platform $1" >&2; exit 1;; esac; }

# The unpacked JDK for a platform (fetched and unpacked once); prints its home directory.
jdk() {
    local t="$1" ext=tar.gz; [[ "$t" == windows-* ]] && ext=zip
    local archive="$CACHE/jdk$JDK_MAJOR-$t.$ext" home="$CACHE/jdk$JDK_MAJOR-$t"
    if [ ! -d "$home" ]; then
        [ -s "$archive" ] || curl -fsSL -o "$archive" "https://api.adoptium.net/v3/binary/latest/$JDK_MAJOR/ga/$(adoptium "$t")/jdk/hotspot/normal/eclipse"
        local x; x=$(mktemp -d "$CACHE/.x-XXXXXX")
        if [ "$ext" = zip ]; then unzip -q "$archive" -d "$x"; else tar xzf "$archive" -C "$x"; fi
        local top; top=$(ls -d "$x"/jdk-* | head -1)
        [ -d "$top/Contents/Home" ] && top="$top/Contents/Home"
        mv "$top" "$home"; rm -rf "$x"
    fi
    echo "$home"
}

host() { case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) echo linux-x64;; Linux-aarch64) echo linux-arm64;; Darwin-arm64) echo macos-arm64;; Darwin-x86_64) echo macos-x64;;
    *) echo "this script runs on Linux or macOS" >&2; exit 1;; esac; }

HOSTJDK=$(jdk "$(host)")          # jlink must be the same major version as the modules it links: use the target JDK's own
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
tar xzf "$SRC" -C "$WORK"
[ -d "$WORK/$TOOL/lib" ] || { echo "$SRC does not unpack to $TOOL/lib" >&2; exit 1; }
MODS=$("$HOSTJDK/bin/jdeps" --print-module-deps --ignore-missing-deps --multi-release "$JDK_MAJOR" -q "$WORK/$TOOL"/lib/*.jar | tail -1)
[ -n "$MODS" ] || { echo "jdeps found no modules" >&2; exit 1; }
EXTRA=jdk.charsets,jdk.localedata,jdk.unsupported,jdk.zipfs,java.management
echo "modules: $MODS + $EXTRA (+ jdk.crypto.ec where the JDK still has it)"

for t in "${TARGETS[@]}"; do
    J=$(jdk "$t")
    OUT="$WORK/$t/$TOOL"; mkdir -p "$WORK/$t"; cp -R "$WORK/$TOOL" "$OUT"
    ADD="$MODS,$EXTRA"; [ -f "$J/jmods/jdk.crypto.ec.jmod" ] && ADD="$ADD,jdk.crypto.ec"
    "$HOSTJDK/bin/jlink" --module-path "$J/jmods" --add-modules "$ADD" --output "$OUT/jre" \
        --strip-java-debug-attributes --no-man-pages --no-header-files --compress zip-6
    # the start scripts prefer the runtime beside them; the person's JAVA_HOME stays for everything else
    for s in "$OUT"/bin/*; do
        case "$s" in
            *.bat) awk -v RS='\r\n' -v ORS='\r\n' '{ print } /^for %%i in \("%APP_HOME%"\) do set APP_HOME=%%~fi/ { print "if exist \"%APP_HOME%\\jre\\bin\\java.exe\" set JAVA_HOME=%APP_HOME%\\jre" }' "$s" > "$s.new" ;;
            *)     awk '{ print } /^APP_HOME=\$\( cd -P/ { print "[ -x \"$APP_HOME/jre/bin/java\" ] && JAVA_HOME=$APP_HOME/jre   # the runtime shipped with this build" }' "$s" > "$s.new"; chmod +x "$s.new" ;;
        esac
        mv "$s.new" "$s"
    done
    grep -q 'jre/bin/java' "$OUT/bin/$TOOL" && grep -q 'jre\\bin\\java.exe' "$OUT/bin/$TOOL.bat" || { echo "start scripts were not patched for $t" >&2; exit 1; }
    tar czf "$DIST/$TOOL-$VER-$t.tar.gz" -C "$WORK/$t" "$TOOL"
    echo "$DIST/$TOOL-$VER-$t.tar.gz  $(du -h "$DIST/$TOOL-$VER-$t.tar.gz" | cut -f1)"
done

#!/usr/bin/env bash
# Install CodeZaiku.
#
#   scripts/install.sh                 install for the current user (~/.local)
#   sudo scripts/install.sh --system   install for everyone (/usr/local)
#   scripts/install.sh --prefix DIR    somewhere else
#   scripts/install.sh --uninstall     remove the program, keep your config and data
#   scripts/install.sh --purge         remove everything, including ~/.codezaiku
#
# Builds a self-contained distribution (JVM launcher + jars + the knowledge library) and puts a
# `codezaiku` command on your PATH. Nothing is downloaded at runtime and no model weights are
# included — point CODEZAIKU_DRIVE at an OpenAI-compatible server and run `codezaiku doctor`.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE=user
PREFIX=""
UNINSTALL=0
PURGE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --system) MODE=system; shift ;;
    --prefix) PREFIX="$2"; MODE=custom; shift 2 ;;
    --uninstall) UNINSTALL=1; shift ;;
    --purge) UNINSTALL=1; PURGE=1; shift ;;
    -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$PREFIX" ]]; then
  if [[ $MODE == system ]]; then PREFIX=/usr/local; else PREFIX="$HOME/.local"; fi
fi
LIBDIR="$PREFIX/share/codezaiku"
BINLINK="$PREFIX/bin/codezaiku"

say() { printf '%s\n' "$*"; }
die() { printf 'install: %s\n' "$*" >&2; exit 1; }

# ── uninstall ───────────────────────────────────────────────────────────────
if [[ $UNINSTALL -eq 1 ]]; then
  removed=0
  [[ -e "$BINLINK" ]] && { rm -f "$BINLINK"; say "removed $BINLINK"; removed=1; }
  [[ -d "$LIBDIR" ]] && { rm -rf "$LIBDIR"; say "removed $LIBDIR"; removed=1; }
  [[ $removed -eq 0 ]] && say "nothing installed under $PREFIX"
  # ~/.codezaiku holds the config, learned cards, research findings and audit trails.
  # An uninstall keeps it — that is the user's data. --purge is the explicit opt-in.
  if [[ $PURGE -eq 1 ]]; then
    if [[ -d "$HOME/.codezaiku" ]]; then
      say ""
      say "PURGE will delete $HOME/.codezaiku, including:"
      [[ -f "$HOME/.codezaiku/config" ]]      && say "  - your config"
      [[ -d "$HOME/.codezaiku/learned" ]]     && say "  - $(find "$HOME/.codezaiku/learned" -name '*.md' 2>/dev/null | wc -l | tr -d ' ') learned fix card(s)"
      [[ -d "$HOME/.codezaiku/research" ]]    && say "  - accumulated research findings"
      [[ -f "$HOME/.codezaiku/audit.jsonl" ]] && say "  - the audit trail of what the operator did"
      say ""
      printf 'type PURGE to confirm: '
      read -r reply
      if [[ "$reply" == PURGE ]]; then
        rm -rf "$HOME/.codezaiku"; say "removed $HOME/.codezaiku"
      else
        say "kept $HOME/.codezaiku"
      fi
    else
      say "nothing at $HOME/.codezaiku"
    fi
  else
    [[ -d "$HOME/.codezaiku" ]] && say "left alone: ~/.codezaiku (config, learned cards, findings, audit trail) — use --purge to remove"
  fi
  exit 0
fi

# ── preflight ───────────────────────────────────────────────────────────────
command -v java >/dev/null 2>&1 || die "java not found — CodeZaiku needs a JDK 21 or newer"
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
[[ "${JAVA_MAJOR:-0}" -ge 21 ]] || die "java $JAVA_MAJOR found, but CodeZaiku needs 21 or newer"

mkdir -p "$PREFIX/bin" 2>/dev/null || die "cannot write to $PREFIX — try --prefix DIR, or sudo with --system"
[[ -w "$PREFIX/bin" ]] || die "no write permission on $PREFIX/bin — try --prefix DIR, or sudo with --system"

# ── build ───────────────────────────────────────────────────────────────────
say "==> building"
cd "$REPO_ROOT"
./gradlew -q :core:installDist --console=plain || die "build failed"
DIST="$REPO_ROOT/core/build/install/codezaiku"
[[ -x "$DIST/bin/codezaiku" ]] || die "build produced no launcher at $DIST/bin/codezaiku"

# ── install ─────────────────────────────────────────────────────────────────
say "==> installing to $LIBDIR"
rm -rf "$LIBDIR"
mkdir -p "$(dirname "$LIBDIR")"
cp -r "$DIST" "$LIBDIR"

# A WRAPPER, not a symlink. MSYS on Windows silently COPIES a file when asked to symlink it, and the
# launcher derives its own install location from $0 — so the copy looked installed, reported success,
# and then failed with ClassNotFoundException because the classpath pointed at a directory that was
# not there. A two-line exec works the same way on every platform and cannot be copied wrong.
mkdir -p "$(dirname "$BINLINK")"
printf '#!/bin/sh\nexec "%s/bin/codezaiku" "$@"\n' "$LIBDIR" > "$BINLINK"
chmod +x "$BINLINK"
say "==> installed launcher $BINLINK"

mkdir -p "$HOME/.codezaiku"

# First install: write a starter config so the next command has something to read.
# An upgrade must never overwrite a config someone has tuned.
CONFIG_PATH="${CODEZAIKU_CONFIG:-$HOME/.codezaiku/config}"
FRESH_CONFIG=0
if [[ ! -f "$CONFIG_PATH" ]]; then
  "$BINLINK" init >/dev/null 2>&1 && FRESH_CONFIG=1
fi

# ── report ──────────────────────────────────────────────────────────────────
cards=$(find "$LIBDIR/ops-knowledge" -name '*.md' 2>/dev/null | wc -l | tr -d ' ')
say ""
say "installed:"
say "  command   $BINLINK"
say "  runtime   $LIBDIR"
say "  cards     $cards ops fix cards + the knowledge packs"
say "  state     ~/.codezaiku  (learned cards, research findings, audit trail)"

# Name the rc file the user's OWN shell reads. Hardcoding ~/.bashrc sent every macOS user to a file
# zsh never loads — the default shell there since Catalina — so following this instruction verbatim
# left `codezaiku` off PATH and the next command not found. Measured on macOS 26.5.
case "${SHELL##*/}" in
  zsh)  RC="~/.zshrc" ;;
  bash) RC="~/.bashrc" ;;
  fish) RC="~/.config/fish/config.fish" ;;
  *)    RC="your shell's startup file" ;;
esac

case ":$PATH:" in
  *":$PREFIX/bin:"*) ;;
  *) say ""
     say "NOTE: $PREFIX/bin is not on your PATH. Add it:"
     if [[ "${SHELL##*/}" == "fish" ]]; then
       say "      fish_add_path $PREFIX/bin"
     else
       say "      echo 'export PATH=\"$PREFIX/bin:\$PATH\"' >> $RC && exec \$SHELL"
     fi ;;
esac

say ""
if [[ $FRESH_CONFIG -eq 1 ]]; then
  say "wrote a starter config at $CONFIG_PATH"
  say ""
  say "next:"
  say "  codezaiku config set drive http://localhost:8200   # your model server"
  say "  codezaiku doctor                                   # what is missing, and the fix"
  say "  codezaiku smoke                                    # confirm the model can call a tool"
else
  say "kept your existing config at $CONFIG_PATH"
  say ""
  say "next:  codezaiku doctor"
fi
say ""
say "  codezaiku help          all commands"
say "  codezaiku config list   current settings and where they came from"

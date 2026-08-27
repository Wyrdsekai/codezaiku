#!/usr/bin/env bash
# CodeZaiku host self-provision — ensure toolchains + language servers, idempotently, WITHOUT containers.
#
# bwrap/Docker are BANNED for runs: both caused kernel-level (IO-path) HOST crashes when run down before.
# We run on the host with timeouts (like the small-model reference harnesses: smallcode, little-coder).
# This script installs/repairs what a run needs so we NEVER hand-provision the box, and a fresh box or a
# reboot self-heals. Everything is plain host file-IO (mise downloads + dep caches) — none of the
# overlayfs/namespace surface that crashed the box. Symlinks land in ~/.local/bin, which is reliably on
# the login PATH that `bash -lc` uses (and the familiar's ShellTool/LspClient use bash -lc).
#
# Safe to run repeatedly. Run on each box once (and the battery calls it).
set -u
BIN="$HOME/.local/bin"; mkdir -p "$BIN"
MISE="$(command -v mise || echo "$HOME/.local/bin/mise")"
works(){ bash -lc "$1" >/dev/null 2>&1; }   # does the command actually RUN (catches broken rustup/mise proxies)

echo "== provisioning toolchains + LSPs (host, no containers) =="

# --- Java: modern gradle on JDK21 via a SHIM. The host `gradle` may be ancient (seen: 4.4.1), and the
#     box JDK may be too new for gradle 8. The CodeZaiku core build needs JDK25 + gradle 9 via ./gradlew,
#     so we must NOT change system java — the shim affects only bare `gradle` (fixtures + the green-gate),
#     never ./gradlew.
"$MISE" use -g java@21 gradle@8 >/dev/null 2>&1
J="$(ls -d "$HOME"/.local/share/mise/installs/java/21.*/ 2>/dev/null | sort -V | tail -1)"
G="$(find "$HOME"/.local/share/mise/installs/gradle -name gradle -type f -path '*/bin/*' 2>/dev/null | sort -V | tail -1)"
if [ -n "$J" ] && [ -n "$G" ]; then
  cat > "$BIN/gradle" <<EOF
#!/usr/bin/env bash
export JAVA_HOME="${J%/}"
exec "$G" "\$@"
EOF
  chmod +x "$BIN/gradle"
fi
works "jdtls --help" || { mkdir -p "$HOME/lsp/jdtls" && \
  curl -fsSL https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz | tar xz -C "$HOME/lsp/jdtls" 2>/dev/null && \
  ln -sf "$HOME/lsp/jdtls/bin/jdtls" "$BIN/jdtls"; }

# --- Rust + rust-analyzer (rustup ships a broken proxy until the component is added) ---
command -v cargo >/dev/null 2>&1 || curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y >/dev/null 2>&1
works "rust-analyzer --version" || rustup component add rust-analyzer >/dev/null 2>&1

# --- Node + TypeScript/JS LSP ---
if ! command -v node >/dev/null 2>&1; then
  "$MISE" use -g node@22 >/dev/null 2>&1
  ln -sf "$(find "$HOME"/.local/share/mise/installs/node -name node -type f -path '*/bin/*' 2>/dev/null | sort -V | tail -1)" "$BIN/node" 2>/dev/null
fi
works "typescript-language-server --version" || npm install -g typescript typescript-language-server >/dev/null 2>&1

# --- Python + pyright ---
command -v python3 >/dev/null 2>&1 || "$MISE" use -g python@3.12 >/dev/null 2>&1
works "pyright-langserver --version" || npm install -g pyright >/dev/null 2>&1
# pyflakes: static undefined-name check for the Python build-gate (py_compile only catches syntax, not a
# NameError / missing import). Pure AST — needs no project deps. PEP-668 system python needs the override.
python3 -m pyflakes --version >/dev/null 2>&1 || python3 -m pip install --user --break-system-packages -q pyflakes >/dev/null 2>&1

# --- Go + gopls ---
command -v go >/dev/null 2>&1 || "$MISE" use -g go@latest >/dev/null 2>&1
works "gopls version" || { GOBIN="$HOME/go/bin" bash -lc "go install golang.org/x/tools/gopls@latest" >/dev/null 2>&1; \
  ln -sf "$HOME/go/bin/gopls" "$BIN/gopls"; }

# --- C/C++ (clangd) ---
command -v clangd >/dev/null 2>&1 || sudo apt-get install -y clangd >/dev/null 2>&1 || true

# --- Godot (gdscript; no standard CLI LSP — runs advance-on-word) ---
works "godot --version" || { mkdir -p "$HOME/lsp/godot" && \
  curl -fsSL https://github.com/godotengine/godot/releases/download/4.3-stable/Godot_v4.3-stable_linux.x86_64.zip -o /tmp/godot.zip && \
  unzip -oq /tmp/godot.zip -d "$HOME/lsp/godot" && \
  ln -sf "$HOME/lsp/godot/Godot_v4.3-stable_linux.x86_64" "$BIN/godot"; }

echo "== resolution (bash -lc — what a run actually sees) =="
bash -lc 'for t in gradle cargo rust-analyzer node typescript-language-server python3 pyright-langserver go gopls clangd godot jdtls; do
  printf "  %-26s" "$t"; command -v "$t" >/dev/null 2>&1 && echo OK || echo MISSING; done'
echo "  gradle ->"; bash -lc "gradle --version 2>/dev/null | grep -E \"Gradle [0-9]\" | head -1" | sed "s/^/    /"

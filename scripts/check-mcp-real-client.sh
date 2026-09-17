#!/usr/bin/env bash
# Connect the official MCP Python SDK client to `codezaiku mcp` and list its tools.
#
# The SDK's Client opens with server/discover (MCP revision 2026-07-28) and falls back to the initialize
# handshake when the server answers "method not found". Only a real client proves that fallback, so run this
# after touching core/src/main/java/org/codezaiku/mcp/McpServer.java or when the SDK has a new major version.
# Needs python3 with venv, and network access to PyPI the first time. It uses a scratch home.
#
# Usage: scripts/check-mcp-real-client.sh [sdk-version]      (default 2.2.0)
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${1:-2.2.0}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
python3 -m venv "$WORK/venv" >/dev/null
"$WORK/venv/bin/pip" -q install "mcp==$SDK" >/dev/null
mkdir -p "$WORK/home"
cat > "$WORK/client.py" <<'PY'
import asyncio, os, sys
from mcp import StdioServerParameters
from mcp.client import Client
work, launcher = sys.argv[1], sys.argv[2]
params = StdioServerParameters(command=launcher, args=["mcp"],
    env=dict(os.environ, CODEZAIKU_CONFIG=work + "/home/config", CODEZAIKU_JAVA_OPTS="-Duser.home=" + work + "/home"))
async def main():
    async with Client(params) as c:
        tools = await c.list_tools()
        names = [t.name for t in tools.tools]
        assert "code" in names and "fix" in names, names
        r = await c.call_tool("show_conventions", {"project": work})
        assert r.content, r
        print("ok: protocol", c.session.protocol_version, "|", len(names), "tools | one call answered")
asyncio.run(main())
PY
"$WORK/venv/bin/python" "$WORK/client.py" "$WORK" "$REPO/bin/codezaiku" 2>"$WORK/err.log" || { tail -20 "$WORK/err.log" >&2; echo "FAILED with mcp==$SDK" >&2; exit 1; }

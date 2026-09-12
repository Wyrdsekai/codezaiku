# @wyrdsekai/codezaiku-mcp

Starts [CodeZaiku](https://codezaiku.org), the coding and development harness for local models and hosted APIs, as an MCP server over
stdio. Any MCP client that can run `npx` can run it:

```json
{ "mcpServers": { "codezaiku": { "command": "npx", "args": ["-y", "@wyrdsekai/codezaiku-mcp"] } } }
```

```
claude mcp add --scope user codezaiku -- npx -y @wyrdsekai/codezaiku-mcp
```

There is no Java in this package and no copy of the program. The launcher finds an installed CodeZaiku and
starts `codezaiku mcp`. When none is installed it fetches the release of the same version from GitHub, checks
it against the release's own `SHA256SUMS` (no sums, no install), unpacks it under `~/.codezaiku/launcher`, and
starts it: the small tarball when Java 21 or newer is on the machine, otherwise the build for this platform that
carries its own runtime, so nothing has to be installed first. The model server is named in `CODEZAIKU_DRIVE` or `~/.codezaiku/config`.

Nothing but the MCP stream is written to stdout. Every message from the launcher goes to stderr.

| variable | meaning |
|---|---|
| `CODEZAIKU_PREFIX` | where the one-line installer put the program (default `~/.local`, or `%LOCALAPPDATA%\Programs`) |
| `CODEZAIKU_DRIVE` | the model server (an OpenAI-style endpoint) |

The version of this package is the version of CodeZaiku it starts. Docs:
[README](https://github.com/Wyrdsekai/codezaiku#readme),
[DEPLOYING_AS_A_BACKEND.md](https://github.com/Wyrdsekai/codezaiku/blob/main/docs/DEPLOYING_AS_A_BACKEND.md).
License: Apache 2.0.

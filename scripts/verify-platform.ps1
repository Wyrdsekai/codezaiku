# CodeZaiku platform pass, Windows. Args: -Dist <dir with the tarballs, SHA256SUMS, install.ps1 AND scripts/stub-drive.py (the run check's model)> -Ver 0.3.16
param([string]$Dist, [string]$Ver)
$ErrorActionPreference = 'Continue'
$script:P = 0; $script:F = 0   # PowerShell names ignore case: no other variable here may be called $p or $f
function Pass($n) { Write-Output "  ok   ${n}"; $script:P++ }
function Fail($n, $m) { Write-Output "  FAIL ${n}: ${m}"; $script:F++ }
function Expect($name, $want, [scriptblock]$run) {
  $out = (& $run 2>&1 | Out-String)
  if ($out -match $want) { Pass $name } else { Fail $name ("wanted '$want', got: " + (($out -split "`n" | Select-Object -Last 3) -join ' ')) }
}
$W = Join-Path $env:TEMP ("czv-" + [guid]::NewGuid().ToString().Substring(0,8)); New-Item -ItemType Directory -Path "$W\www" | Out-Null
$env:CODEZAIKU_PREFIX = "$W\prefix"; $env:HOME = "$W\home"; $env:USERPROFILE = "$W\home"; New-Item -ItemType Directory -Path "$W\home" | Out-Null
$env:Path = 'C:\tools\jdk25\bin;' + $env:Path
foreach ($g in 'C:\Program Files\Git\cmd', 'C:\Program Files\Git\bin') { if (Test-Path $g) { $env:Path = "$g;" + $env:Path } }   # an ssh session has no user PATH, and the shell tool needs Git for Windows' bash
Copy-Item "$Dist\codezaiku-$Ver*.tar.gz" "$W\www\"; Copy-Item "$Dist\SHA256SUMS" "$W\www\"
$port = Get-Random -Minimum 20000 -Maximum 40000
$http = Start-Process -FilePath python -ArgumentList "-m http.server $port --bind 127.0.0.1" -WorkingDirectory "$W\www" -PassThru -WindowStyle Hidden
Start-Sleep 2
$env:CODEZAIKU_DOWNLOAD_BASE = "http://127.0.0.1:$port"; $env:CODEZAIKU_VERSION = $Ver
Write-Output "== install $Ver from install.ps1 (local server)"
$out = (& "$Dist\install.ps1" 2>&1 | Out-String); if ($LASTEXITCODE -eq 0 -or $out -match "codezaiku $Ver") { Pass 'install' } else { Fail 'install' (($out -split "`n" | Select-Object -Last 2) -join ' ') }
$Z = Get-ChildItem -Path $env:CODEZAIKU_PREFIX -Recurse -Filter codezaiku.bat | Select-Object -First 1 -ExpandProperty FullName
if (-not $Z) { Fail 'find codezaiku.bat' "nothing under $env:CODEZAIKU_PREFIX"; Write-Output "  $P passed, $F failed"; exit 1 }
Expect 'version' $Ver { & $Z --version }
Expect 'doctor names this version' "codezaiku $Ver" { & $Z doctor }
Write-Output "== the MCP server says which release it is"
$init = "$W\init.json"
Set-Content -Path $init -Value '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}' -NoNewline
$proc = Start-Process -FilePath cmd.exe -ArgumentList "/c `"type $init | $Z mcp > $W\mcp-out.txt 2> $W\mcp-err.txt`"" -PassThru -WindowStyle Hidden
if (-not $proc.WaitForExit(90000)) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
$mcp = (Get-Content "$W\mcp-out.txt" -ErrorAction SilentlyContinue | Out-String)
if ($mcp -match "`"serverInfo`":\{`"name`":`"codezaiku`",`"version`":`"$Ver`"\}") { Pass "mcp serverInfo version $Ver" } else { Fail "mcp serverInfo version $Ver" (($mcp -split "`n" | Select-Object -First 2) -join ' ') }
Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$W*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Output "== the build with its own Java runtime"
$RT = "$W\prefix-rt"
$env:CODEZAIKU_RUNTIME = '1'; $env:CODEZAIKU_PREFIX = $RT
$out = (& "$Dist\install.ps1" 2>&1 | Out-String); if ($LASTEXITCODE -eq 0 -and (Test-Path "$RT\codezaiku\jre\bin\java.exe")) { Pass 'install (own runtime)' } else { Fail 'install (own runtime)' (($out -split "`n" | Select-Object -Last 3) -join ' ') }
Remove-Item Env:CODEZAIKU_RUNTIME; Remove-Item Env:CODEZAIKU_VERSION
$Z2 = "$RT\codezaiku\bin\codezaiku.bat"
$env:JAVA_HOME = 'C:\nonexistent'
Expect 'version (own runtime, JAVA_HOME pointing nowhere)' $Ver { & $Z2 --version }
Remove-Item Env:JAVA_HOME
Write-Output "== run: a file made through the shell is in files[] (the harness's own git calls, on this platform)"
$repo = "$W\repo"; New-Item -ItemType Directory -Path $repo | Out-Null
git -C $repo init -q 2>$null; Set-Content -Path "$repo\seed.txt" -Value 'seed'
git -C $repo add -A 2>$null; git -C $repo -c user.email=t@example.com -c user.name=t commit -qm base 2>$null
$script = "$W\script.json"
Set-Content -Path $script -Value '[{"tool":"shell","args":{"command":"echo hi > made.txt"}},{"tool":"task_done","args":{"summary":"made made.txt"}}]' -NoNewline
$sport = Get-Random -Minimum 20000 -Maximum 40000
$stub = Start-Process -FilePath python -ArgumentList "`"$Dist\stub-drive.py`" $sport `"$script`"" -PassThru -WindowStyle Hidden
Start-Sleep 2
Push-Location $repo
$runOut = (& $Z2 run --text "Make made.txt with the shell." --output-format json --no-session -q --max-turns 6 --task-id win-1 --drive "http://127.0.0.1:$sport" 2>$null | Out-String)
Pop-Location
Stop-Process -Id $stub.Id -Force -ErrorAction SilentlyContinue
if ((Test-Path "$repo\made.txt") -and ($runOut -match 'made\.txt') -and ($runOut -notmatch 'seed\.txt')) { Pass 'run: files[] has the shell-made file and not the committed one' } else { Fail 'run: files[]' (($runOut -split "`n" | Select-Object -Last 4) -join ' ') }
Write-Output "== ACP: session/new starts a client's stdio MCP server and offers the model option"
$acpIn = "$W\acp-in.txt"
$j1 = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}'
$j2 = '{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"' + $repo.Replace('\','\\') + '","mcpServers":[{"name":"self","command":"' + $Z2.Replace('\','\\') + '","args":["mcp"],"env":[{"name":"CZ_PLATFORM_CHECK","value":"1"}]}]}}'
Set-Content -Path $acpIn -Value @($j1, $j2)
$proc = Start-Process -FilePath cmd.exe -ArgumentList "/c `"type $acpIn | $Z2 acp > $W\acp-out.txt 2> $W\acp-err.txt`"" -PassThru -WindowStyle Hidden
if (-not $proc.WaitForExit(180000)) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
$acp = (Get-Content "$W\acp-out.txt" -ErrorAction SilentlyContinue | Where-Object { $_ -match '"id":2' } | Out-String)
if ($acp -match '"sessionId"') { Pass 'ACP: the MCP server started' } else { Fail 'ACP: the MCP server started' $acp }
if ($acp -match '"category":"model"') { Pass 'ACP: model option offered' } else { Fail 'ACP: model option offered' $acp }
# the chat in the browser: on a port, its list and token under the work folder, the model a closed port ($home is PowerShell's own: never assign it)
$env:CODEZAIKU_WEB_DIR = "$W\web"; $env:CODEZAIKU_CHAT_DIR = "$W\chat"
$webPort = Get-Random -Minimum 7300 -Maximum 7999
$web = Start-Process -FilePath cmd.exe -ArgumentList "/c `"$Z2 web $W\repo --port $webPort --host 127.0.0.1 --drive http://127.0.0.1:9 > $W\web-out.txt 2>&1`"" -PassThru -WindowStyle Hidden
$webUp = $false
for ($i = 0; $i -lt 60 -and -not $webUp; $i++) { try { $r = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$webPort/health" -TimeoutSec 3; if ($r.StatusCode -eq 200) { $webUp = $true } } catch { Start-Sleep -Seconds 1 } }
if ($webUp) { Pass 'web: answers on its port'
  $front = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$webPort/" -TimeoutSec 5).Content
  if ($front -match 'CodeZaiku' -and $front -match '/chat\?project=') { Pass 'web: the front page lists the project' } else { Fail 'web: the front page' $front.Substring(0, [Math]::Min(160, $front.Length)) }
  if ($front -match '/chat\?project=([^"]+)"') { $proj = $Matches[1]; $chat = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$webPort/chat?project=$proj" -TimeoutSec 10).Content
    if ($chat -match 'id="composer"') { Pass "web: the project's page carries the box to type" } else { Fail "web: the project's page" '' } }
  if (Test-Path "$W\web\projects") { Pass 'web: the project list is kept under the work folder, not the home' } else { Fail 'web: the list went elsewhere' '' }
} else { Fail "web: nothing answered on port $webPort in 60 s" (Get-Content "$W\web-out.txt" -ErrorAction SilentlyContinue | Select-Object -Last 3 | Out-String) }
Stop-Process -Id $web.Id -Force -ErrorAction SilentlyContinue
Remove-Item Env:CODEZAIKU_WEB_DIR -ErrorAction SilentlyContinue; Remove-Item Env:CODEZAIKU_CHAT_DIR -ErrorAction SilentlyContinue
Stop-Process -Id $http.Id -Force -ErrorAction SilentlyContinue
Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$W*" } | Stop-Process -Force -ErrorAction SilentlyContinue
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath) { [Environment]::SetEnvironmentVariable('Path', (($userPath -split ';') | Where-Object { $_ -and -not $_.StartsWith($W) }) -join ';', 'User') }
if ($F -eq 0) { Remove-Item -Recurse -Force $W -ErrorAction SilentlyContinue; Write-Output "  $P passed, $F failed" } else { Write-Output "  $P passed, $F failed   (work dir kept: $W)" }

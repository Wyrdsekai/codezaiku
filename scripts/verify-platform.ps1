# CodeZaiku platform pass, Windows. Args: -Dist <dir with tarballs, SHA256SUMS, install.ps1> -Ver 0.3.5
param([string]$Dist, [string]$Ver)
$ErrorActionPreference = 'Continue'
$script:P = 0; $script:F = 0
function Pass($n) { Write-Output "  ok   ${n}"; $script:P++ }
function Fail($n, $m) { Write-Output "  FAIL ${n}: ${m}"; $script:F++ }
function Expect($name, $want, [scriptblock]$run) {
  $out = (& $run 2>&1 | Out-String)
  if ($out -match $want) { Pass $name } else { Fail $name ("wanted '$want', got: " + (($out -split "`n" | Select-Object -Last 3) -join ' ')) }
}
$W = Join-Path $env:TEMP ("czv-" + [guid]::NewGuid().ToString().Substring(0,8)); New-Item -ItemType Directory -Path "$W\www" | Out-Null
$env:CODEZAIKU_PREFIX = "$W\prefix"; $env:HOME = "$W\home"; $env:USERPROFILE = "$W\home"; New-Item -ItemType Directory -Path "$W\home" | Out-Null
$env:Path = 'C:\tools\jdk25\bin;' + $env:Path
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
$p = Start-Process -FilePath cmd.exe -ArgumentList "/c `"type $init | $Z mcp > $W\mcp-out.txt 2> $W\mcp-err.txt`"" -PassThru -WindowStyle Hidden
if (-not $p.WaitForExit(90000)) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
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
Stop-Process -Id $http.Id -Force -ErrorAction SilentlyContinue
Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$W*" } | Stop-Process -Force -ErrorAction SilentlyContinue
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($userPath) { [Environment]::SetEnvironmentVariable('Path', (($userPath -split ';') | Where-Object { $_ -and -not $_.StartsWith($W) }) -join ';', 'User') }
if ($F -eq 0) { Remove-Item -Recurse -Force $W -ErrorAction SilentlyContinue; Write-Output "  $P passed, $F failed" } else { Write-Output "  $P passed, $F failed   (work dir kept: $W)" }

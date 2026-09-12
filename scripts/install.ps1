# CodeZaiku one-line installer for Windows.
#
# ASCII ONLY, deliberately. Windows PowerShell 5.1 reads a BOM-less .ps1 in the system
# codepage, so a UTF-8 em-dash arrives as mojibake that injects a stray quote and breaks
# string parsing several lines later. Measured: this script failed with
# 'The string is missing the terminator' on a line whose only sin was punctuation.
#
#   irm https://codezaiku.org/install.ps1 | iex
#
# Downloads the release tarball from GitHub, VERIFIES it against the release's own SHA256SUMS, and
# installs it. Only this script comes from wherever you fetched it -- the artifact and the checksums
# both come from the same GitHub release, so this script cannot hand you a payload those checksums
# do not match. Fetching it, reading it, then running it is the better habit.
#
#   $env:CODEZAIKU_VERSION = '0.1.0'    a specific release instead of the latest
#   $env:CODEZAIKU_PREFIX  = 'C:\Tools' where to install (default: %LOCALAPPDATA%\Programs)
$ErrorActionPreference = 'Stop'

$repo = 'Wyrdsekai/codezaiku'
$base = $env:CODEZAIKU_DOWNLOAD_BASE          # test hook; empty means GitHub
$prefix = if ($env:CODEZAIKU_PREFIX) { $env:CODEZAIKU_PREFIX }
          else { Join-Path $env:LOCALAPPDATA 'Programs' }

# NOT Write-Error. That renders a full PowerShell error record -- 'Die : codezaiku: java not
# found', then 'At line:28 char:19', a source excerpt with a squiggle, and a CategoryInfo block.
# For an installer it is the first thing a new user ever sees from us, and it reads as the script
# crashing rather than as the one-line prerequisite check it is. Measured on Windows 11 against the
# published 0.1.0 script. The shell installer's `die` has always printed one line to stderr; this
# is the odd one out.
function Die($m) { [Console]::Error.WriteLine("codezaiku: $m"); exit 1 }

# Java 21 or newer on the machine gets the small tarball. Without it -- or with $env:CODEZAIKU_RUNTIME set -- the
# x64 build that carries its own Java runtime is installed instead, when the release has one.
$javaOk = $false; $jv = 0
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    # java writes its version to STDERR, and with ErrorActionPreference=Stop PowerShell treats any
    # native stderr output as a terminating error -- so probing the JDK aborted the installer on a
    # perfectly good JDK. Relax the preference for this one call, not for the whole script.
    $jvLine = & { $ErrorActionPreference = 'Continue'; (& java -version) 2>&1 | Select-Object -First 1 }
    $jv = "$jvLine" -replace '.*version "(\d+).*', '$1'
    if ([int]$jv -ge 21) { $javaOk = $true }
}
function NoJava { if (-not $java) { Die 'java not found -- CodeZaiku needs a JRE or JDK 21 or newer on PATH' } else { Die "java $jv found -- CodeZaiku needs 21 or newer" } }
$runtime = ''
if ($env:CODEZAIKU_RUNTIME -or -not $javaOk) {
    $arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
    if ("$arch" -eq 'X64') { $runtime = 'windows-x64' } else { NoJava }
}

# The harness shells out through bash, and Git for Windows supplies it. Without it the install
# succeeds and every command that touches the shell fails later -- say so now, not then.
if (-not (Test-Path 'C:\Program Files\Git\bin\bash.exe')) {
    Write-Warning 'Git for Windows was not found. CodeZaiku shells out through its bash; install it from https://git-scm.com/download/win or most commands will fail.'
}

$ver = $env:CODEZAIKU_VERSION
if (-not $ver -and -not $base) {
    $rel = Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest"
    $ver = $rel.tag_name -replace '^v', ''
    if (-not $ver) { Die 'could not determine the latest release -- set CODEZAIKU_VERSION' }
}
if (-not $base) { $base = "https://github.com/$repo/releases/download/v$ver" }

$tar = "codezaiku-$ver.tar.gz"
if ($runtime) { $tar = "codezaiku-$ver-$runtime.tar.gz"; Write-Host "codezaiku: installing the $runtime build, which carries its own Java runtime" }
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("codezaiku-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Write-Host "codezaiku: downloading $tar"
    try { Invoke-WebRequest "$base/$tar" -OutFile "$tmp\$tar" -UseBasicParsing }
    catch { if ($runtime) { [Console]::Error.WriteLine("codezaiku: this release has no build with its own runtime for $runtime ($base/$tar)"); NoJava } else { Die "download failed: $base/$tar ($_)" } }
    Invoke-WebRequest "$base/SHA256SUMS" -OutFile "$tmp\SHA256SUMS" -UseBasicParsing

    # An artifact that does not match is not installed: a partial download and a substituted one
    # look identical until you check.
    $line = Get-Content "$tmp\SHA256SUMS" | Where-Object { $_ -match [regex]::Escape($tar) } | Select-Object -First 1
    if (-not $line) { Die "$tar is not listed in SHA256SUMS" }
    $expect = ($line -split '\s+')[0].ToLower()
    $actual = (Get-FileHash "$tmp\$tar" -Algorithm SHA256).Hash.ToLower()
    if ($expect -ne $actual) { Die "checksum mismatch for $tar -- refusing to install`n  expected $expect`n  got      $actual" }
    Write-Host 'codezaiku: checksum verified'

    $dest = Join-Path $prefix 'codezaiku'
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    New-Item -ItemType Directory -Path $prefix -Force | Out-Null
    tar -xzf "$tmp\$tar" -C $prefix          # unpacks a top-level 'codezaiku'

    $bin = Join-Path $dest 'bin'
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath -notlike "*$bin*") {
        [Environment]::SetEnvironmentVariable('Path', "$bin;$userPath", 'User')
        Write-Host "codezaiku: added $bin to your user PATH (open a new terminal to pick it up)"
    }
    Write-Host "codezaiku: installed $dest"
    & "$bin\codezaiku.bat" --version
    Write-Host "`nnext:  codezaiku setup       # the model, web search and your editors agent, in a few questions"
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

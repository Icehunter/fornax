# Windows port of scripts/deploy.sh: builds Fornax and deploys the resulting Fabric jar into a
# launcher profile, optionally linking a pack checkout into that profile's shaderpacks/.
#
# LOCAL-ONLY: this script only builds and copies files on disk. It does not touch git, does not
# commit, does not push. Safe to re-run any time.
#
#   $env:FORNAX_PROFILE = "$env:APPDATA\ModrinthApp\profiles\Fabulously Optimized"
#   $env:FORNAX_LINK_PACK = "C:\Users\Icehunter\source\plague"
#   .\scripts\deploy.ps1
#
# or equivalently:
#
#   .\scripts\deploy.ps1 -ProfileDir "...\profiles\Fabulously Optimized" -LinkPack "...\source\plague"
#
# Why a separate script rather than running deploy.sh under Git Bash: deploy.sh is macOS-specific in
# four places that all matter -- `md5 -q`, `shasum`, `pgrep -x "Modrinth App"`, and the
# `~/Library/Application Support` launcher paths -- plus the two platform differences below.
[CmdletBinding()]
param(
    [string]$ProfileDir = $env:FORNAX_PROFILE,
    [string]$LinkPack = $env:FORNAX_LINK_PACK,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if (-not $ProfileDir) {
    Write-Error @'
FORNAX_PROFILE is not set. Point it at the launcher profile to deploy into:
  $env:FORNAX_PROFILE = "$env:APPDATA\ModrinthApp\profiles\<name>"; .\scripts\deploy.ps1
'@
}
if (-not (Test-Path -LiteralPath $ProfileDir -PathType Container)) {
    Write-Error "FORNAX_PROFILE is not a directory: $ProfileDir"
}

# --- Java ---------------------------------------------------------------------------------------
# Gradle needs a JDK 25; the Modrinth launcher ships only a JRE, so a machine that can run the game
# still cannot build the mod. Honour JAVA_HOME when it points at a real JDK, else pick the newest
# JDK unpacked under ~\.jdks, else say exactly what is missing.
function Resolve-Jdk {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) {
        return $env:JAVA_HOME
    }
    $candidate = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath "$($_.FullName)\bin\javac.exe" } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    if (Get-Command javac -ErrorAction SilentlyContinue) { return $null }  # a JDK is on PATH already
    Write-Error @'
No JDK found. Fornax needs JDK 25 to build (the launcher's bundled Java is a JRE and cannot compile).
Unpack one under %USERPROFILE%\.jdks, or set JAVA_HOME, then re-run. For example:
  Invoke-WebRequest https://cdn.azul.com/zulu/bin/zulu25.36.205-ca-jdk25.0.4.1-win_x64.zip -OutFile $env:TEMP\zulu25.zip
  Expand-Archive $env:TEMP\zulu25.zip -DestinationPath $env:USERPROFILE\.jdks
'@
}

if (-not $SkipBuild) {
    $jdk = Resolve-Jdk
    if ($jdk) {
        $env:JAVA_HOME = $jdk
        Write-Host "=== JDK: $jdk"
    }
    Write-Host "=== Building fornax ==="
    Push-Location $repo
    try {
        & "$repo\gradlew.bat" build
        if ($LASTEXITCODE -ne 0) { Write-Error "gradlew build failed (exit $LASTEXITCODE)" }
    } finally { Pop-Location }
}

Write-Host ''
Write-Host '=== Locating built jar ==='
$libsDir = Join-Path $repo 'build\libs'
if (-not (Test-Path -LiteralPath $libsDir -PathType Container)) {
    Write-Error "expected build output dir not found: $libsDir"
}

# Exclude -sources and -dev jars: Loom's remap step can leave those alongside the final shipping jar.
$candidates = @(Get-ChildItem $libsDir -Filter 'fornax-*.jar' -File |
    Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-dev.jar' } |
    Sort-Object Name)

if ($candidates.Count -eq 0) { Write-Error "no candidate jar found in $libsDir" }
if ($candidates.Count -gt 1) {
    Write-Error ("expected exactly one jar in ${libsDir}, found $($candidates.Count):`n  " +
        (($candidates | ForEach-Object { $_.Name }) -join "`n  "))
}

$jar       = $candidates[0]
$buildHash = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash

# The deployed name is the jar's own, deliberately STABLE across builds -- same reasoning as
# deploy.sh: a build-unique filename orphans the path a launcher may already have indexed, and the
# instance then refuses to start on that dead row while the new jar sits beside it, unused.
$deployName = $jar.Name

Write-Host "  found: $($jar.Name)"
Write-Host "  sha256: $($buildHash.Substring(0,16))..."
Write-Host "  deploy as: $deployName (stable path)"

Write-Host ''
Write-Host "=== Deploying to $ProfileDir\mods ==="
$modsDir = Join-Path $ProfileDir 'mods'
New-Item -ItemType Directory -Force -Path $modsDir | Out-Null

# Retire every fornax jar EXCEPT the stable deploy path, which is overwritten in place below.
$retiredDir = Join-Path $ProfileDir 'mods-retired'
New-Item -ItemType Directory -Force -Path $retiredDir | Out-Null
Get-ChildItem $modsDir -Filter 'fornax-*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $deployName } |
    ForEach-Object {
        Write-Host "  retiring: $($_.Name) -> mods-retired\"
        Move-Item -LiteralPath $_.FullName -Destination (Join-Path $retiredDir $_.Name) -Force
    }

$deployPath = Join-Path $modsDir $deployName
Copy-Item -LiteralPath $jar.FullName -Destination $deployPath -Force

# No chmod here. deploy.sh sets 0600 because the macOS launcher's content store refused to adopt a
# 0644 jar; POSIX modes do not apply on Windows and the launcher reads the file as written.

# Verify what landed rather than trusting the copy: a truncated or partially written jar is exactly
# the failure the launcher's own integrity check would report next.
$deployedHash = (Get-FileHash -LiteralPath $deployPath -Algorithm SHA256).Hash
if ($deployedHash -ne $buildHash) {
    Write-Error "deployed jar does not match the build`n  built:    $buildHash`n  deployed: $deployedHash"
}
Write-Host ''
Write-Host "  verified sha256: $($deployedHash.Substring(0,16))..."

# --- Optional pack link -------------------------------------------------------------------------
# A DIRECTORY JUNCTION, not a symlink: creating a symlink on Windows needs either Administrator or
# Developer Mode, while `mklink /J` works as a plain user. The launcher and the engine both see a
# junction as an ordinary directory, and the launcher's file index skips it the same way it skips
# any unmanaged shaderpack directory.
if ($LinkPack) {
    if (-not (Test-Path -LiteralPath $LinkPack -PathType Container)) {
        Write-Error "FORNAX_LINK_PACK is set but is not a directory: $LinkPack"
    }
    $linkName = Split-Path -Leaf $LinkPack
    $shaderDir = Join-Path $ProfileDir 'shaderpacks'
    $linkPath = Join-Path $shaderDir $linkName
    Write-Host ''
    Write-Host "=== Linking $linkName into $shaderDir ==="
    New-Item -ItemType Directory -Force -Path $shaderDir | Out-Null

    if (Test-Path -LiteralPath $linkPath) {
        $existing = Get-Item -LiteralPath $linkPath -Force
        # Replace a link we previously made; never delete a real pack directory that happens to
        # share the name, which is the one way this script could destroy someone's work.
        if ($existing.LinkType) {
            (Get-Item -LiteralPath $linkPath -Force).Delete()
        } else {
            Write-Error "$linkPath already exists and is not a link; refusing to replace it"
        }
    }
    & cmd /c mklink /J "`"$linkPath`"" "`"$LinkPack`"" | Out-Null
    if (-not (Test-Path -LiteralPath $linkPath)) { Write-Error "failed to create junction at $linkPath" }
    Write-Host "  linked: $linkPath -> $LinkPack"
}

# --- Launcher index -----------------------------------------------------------------------------
# Modrinth App keeps a SQLite index of every file under a profile (path, sha1, size, plus a
# `missing` flag) and validates it before launching. A hand-dropped jar does NOT stay outside that
# index: the app picks it up on its next scan and gives it a row like any other mod, so an in-place
# overwrite leaves that row's sha1 and size describing the previous build.
#
# The Windows database has no store_blobs / store_instance_files tables, so the content-store blob
# adoption deploy.sh performs has nothing to write to here and is left out. What remains portable is
# the rest: re-stamp the row for the path just written, and drop rows whose file is genuinely gone.
#
# Observed on this launcher build: after an in-place overwrite it re-hashes the file on its own scan
# and the row's sha1/size follow the new jar, with `missing` staying 0 -- it does not refuse to launch
# the way the macOS content-store build does. So this section is a fallback for when that scan has
# not happened yet, not a precondition for launching, and skipping it is not a failure.
#
# Writing is conditional on two things, both for the reason deploy.sh gives: sqlite3 has to be on
# PATH, and the app must not be running, because writing under it risks a corrupt WAL and it would
# overwrite the change anyway.
#
# Set FORNAX_SKIP_LAUNCHER_INDEX=1 to leave the database untouched.
if (-not $env:FORNAX_SKIP_LAUNCHER_INDEX) {
    $appDb = Join-Path $env:APPDATA 'ModrinthApp\app.db'
    # No ?. here: Windows PowerShell 5.1 has no null-conditional operator.
    $sqliteCmd = Get-Command sqlite3 -ErrorAction SilentlyContinue
    $sqlite = if ($sqliteCmd) { $sqliteCmd.Source } else { $null }
    $appRunning = [bool](Get-Process -Name 'Modrinth App' -ErrorAction SilentlyContinue)
    Write-Host ''
    if (-not (Test-Path -LiteralPath $appDb)) {
        Write-Host "  launcher index: skipped (no Modrinth App database at $appDb)"
    } elseif (-not $sqlite) {
        Write-Host '  launcher index: skipped (no sqlite3 on PATH)'
    } elseif ($appRunning) {
        Write-Warning 'launcher index: SKIPPED - Modrinth App is running.'
        Write-Warning "  The row for mods/$deployName still describes the previous build. The app re-hashes"
        Write-Warning '  on its own scan; if it ever refuses to launch asking for a repair, close it and re-run.'
    } else {
        $profileKey = (Split-Path -Leaf $ProfileDir).Replace("'", "''")
        $instanceId = (& $sqlite -readonly $appDb "select id from instances where path='$profileKey';") -replace "`r", ''
        if (-not $instanceId) {
            Write-Host "  launcher index: skipped (no instance registered for '$profileKey')"
        } else {
            Write-Host "=== Reconciling launcher index for $profileKey ==="
            $sha1 = (Get-FileHash -LiteralPath $deployPath -Algorithm SHA1).Hash.ToLower()
            $size = (Get-Item -LiteralPath $deployPath).Length
            $rel = "mods/$deployName".Replace("'", "''")
            & $sqlite $appDb "update instance_files set sha1='$sha1', size=$size, missing=0 where instance_id='$instanceId' and relative_path='$rel';"
            Write-Host "  re-stamped mods/$deployName ($($sha1.Substring(0,12))..., $size bytes)"

            # Checked one at a time against the filesystem rather than deleted by flag: a row flagged
            # missing whose file EXISTS is a stale flag to clear, not a row to delete, and deleting it
            # would unregister real content.
            $orphans = 0
            $flagged = @(& $sqlite -readonly $appDb "select relative_path from instance_files where instance_id='$instanceId' and missing=1;")
            foreach ($row in $flagged) {
                $row = $row -replace "`r", ''
                if (-not $row) { continue }
                $esc = $row.Replace("'", "''")
                if (Test-Path -LiteralPath (Join-Path $ProfileDir $row)) {
                    & $sqlite $appDb "update instance_files set missing=0 where instance_id='$instanceId' and relative_path='$esc';"
                    Write-Host "  un-flagged (file present): $row"
                } else {
                    & $sqlite $appDb "delete from instance_files where instance_id='$instanceId' and relative_path='$esc';"
                    $orphans++
                }
            }
            if ($orphans -gt 0) { Write-Host "  removed $orphans orphaned row(s) for files no longer on disk" }
        }
    }
}

Write-Host ''
Write-Host "Deployed: $deployName"
Write-Host 'Nothing was committed or pushed - this script only touches the filesystem.'

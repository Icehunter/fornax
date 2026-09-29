# Windows port of scripts/deploy.sh: builds Fornax and deploys the resulting Fabric jar into a
# launcher profile, optionally linking a pack checkout into that profile's shaderpacks/.
#
# LOCAL-ONLY: this script only builds and copies files on disk. It does not touch git, does not
# commit, push, or access the launcher database or content store.
#
#   $env:FORNAX_PROFILE = "$env:APPDATA\ModrinthApp\profiles\Fabulously Optimized"
#   $env:FORNAX_LINK_PACK = "C:\Users\Icehunter\source\plague"
#   .\scripts\deploy.ps1
#
# or equivalently:
#
#   .\scripts\deploy.ps1 -ProfileDir "...\profiles\Fabulously Optimized" -LinkPack "...\source\plague"
#
# Uses native PowerShell paths, checksums and directory junctions on Windows.
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

# Keep one stable jar path across builds. The launcher manages its own index.
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

# POSIX file modes do not apply on Windows.

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
# junction as an ordinary directory.
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

Write-Host ''
Write-Host "Deployed: $deployName"
Write-Host 'Nothing was committed or pushed - this script only touches the filesystem.'

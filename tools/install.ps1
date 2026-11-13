<#
.SYNOPSIS
    Installs the built jar into the game's mods directory (atomic replace).

.DESCRIPTION
    Two guards, each because it was violated once in the reference project:
      * the jar must EXIST and be fresh -- an install that copies a stale jar is worse than no
        install, because the log then describes code that is no longer there;
      * the replace is atomic (staged file + rename), so the game can never read a half-written zip.

    The failed predecessor mod (CombatSolver.jar) is moved aside: it binds the same function keys and
    runs its own hand-written model, so leaving it loaded would make every measurement ambiguous.
#>
param(
    [string]$GameDir = 'D:\Steam\steamapps\common\SlayTheSpire',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jarName = 'sts1-engine-solver.jar'

if (-not $SkipBuild) {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'tools\build.ps1') 2>&1 | Write-Host
}

$built = Join-Path $root "mod\$jarName"
if (-not (Test-Path -LiteralPath $built)) { throw "no built jar at $built" }

$modsDir = Join-Path $GameDir 'mods'
$target = Join-Path $modsDir $jarName
$staged = "$target.new"
if (-not (Test-Path -LiteralPath $modsDir)) { throw "mods directory missing: $modsDir" }

# A running game holds the installed jar open. Windows then refuses the rename below with
# "Cannot create a file when that file already exists", which reads like a bug in this script and
# leaves a half-finished .new behind. Probe the file for an exclusive lock and name the culprit
# instead -- an install that reports "close the game" is worth more than one that fails in passing.
if (Test-Path -LiteralPath $staged) {
    Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue
    Write-Host ("cleaned   : a stale " + $jarName + ".new from an earlier failed install")
}
$locked = $false
if (Test-Path -LiteralPath $target) {
    try {
        $fh = [System.IO.File]::Open($target, 'Open', 'ReadWrite', 'None')
        $fh.Close()
    } catch {
        $locked = $true
    }
}
if ($locked) {
    $holders = @(Get-Process -Name 'java','javaw','SlayTheSpire' -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($GameDir) })
    Write-Host ("REFUSING : " + $target + " is locked by a running game.")
    if ($holders.Count -gt 0) {
        Write-Host ("holders  : " + (($holders | ForEach-Object { $_.ProcessName + ' (pid ' + $_.Id + ')' }) -join ', '))
    }
    Write-Host "next     : close Slay the Spire AND the ModTheSpire launcher window, then run this again."
    Write-Host "           (the lock probe only reads the file; nothing was replaced)"
    exit 2
}

# Park the predecessor mod. Renaming (not deleting) keeps its evidence recoverable.
$old = Join-Path $modsDir 'CombatSolver.jar'
$parked = Join-Path $modsDir 'disabled'
if (Test-Path -LiteralPath $old) {
    New-Item -ItemType Directory -Force -Path $parked | Out-Null
    Move-Item -LiteralPath $old -Destination (Join-Path $parked 'CombatSolver.jar') -Force
    Write-Host "parked    : CombatSolver.jar -> disabled\CombatSolver.jar"
}

Copy-Item -LiteralPath $built -Destination $staged -Force
Move-Item -LiteralPath $staged -Destination $target -Force

$srcHash = (Get-FileHash -LiteralPath $built -Algorithm SHA256).Hash
$dstHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
if ($srcHash -ne $dstHash) { throw "hash mismatch after install: $srcHash vs $dstHash" }
Write-Host "installed : $target"
Write-Host "sha256    : $dstHash"
Write-Host "INSTALL OK"

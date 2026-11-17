# Save / preferences snapshot for the STS1 engine solver.
# The mod never touches the run save, but a test run can be ruined by the game itself, so every
# change gets a restore point: this script copies the run autosave and the preference files into
# %USERPROFILE%\spire-pilot\save-backups\<timestamp>\ before a jar is replaced.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\save-backup.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\save-backup.ps1 -Restore <folder>
param(
    [string]$Restore = "",
    [string]$Game = "D:\Steam\steamapps\common\SlayTheSpire"
)
$ErrorActionPreference = "Stop"
$root = Join-Path $env:USERPROFILE "spire-pilot\save-backups"
$saves = Join-Path $Game "saves"
$prefs = Join-Path $Game "preferences"

if ($Restore -ne "") {
    if (-not (Test-Path $Restore)) { Write-Host "REFUSING: no such backup folder: $Restore"; exit 2 }
    $src = Join-Path $Restore "saves"
    if (Test-Path $src) {
        Copy-Item (Join-Path $src "*") $saves -Force
        Write-Host "restored run save(s) from $src"
    }
    $srcp = Join-Path $Restore "preferences"
    if (Test-Path $srcp) {
        Copy-Item (Join-Path $srcp "*") $prefs -Force
        Write-Host "restored preference file(s) from $srcp"
    }
    Write-Host "RESTORE DONE"
    exit 0
}

if (-not (Test-Path $saves)) { Write-Host "REFUSING: no saves folder at $saves"; exit 2 }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dest = Join-Path $root $stamp
New-Item -ItemType Directory -Path (Join-Path $dest "saves") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $dest "preferences") -Force | Out-Null
$n = 0
Get-ChildItem $saves -File | ForEach-Object { Copy-Item $_.FullName (Join-Path $dest "saves") -Force; $n++ }
$p = 0
if (Test-Path $prefs) {
    Get-ChildItem $prefs -File | ForEach-Object { Copy-Item $_.FullName (Join-Path $dest "preferences") -Force; $p++ }
}
$hasRun = (Get-ChildItem (Join-Path $dest "saves") -File | Where-Object { $_.Name -like "*.autosave" } | Measure-Object).Count
Write-Host ("backup: " + $dest)
Write-Host ("backup: " + $n + " save file(s), " + $p + " preference file(s), autosave present=" + ($hasRun -gt 0))
Write-Host ("restore with: powershell -NoProfile -ExecutionPolicy Bypass -File tools\save-backup.ps1 -Restore '" + $dest + "'")
Write-Host "BACKUP DONE"

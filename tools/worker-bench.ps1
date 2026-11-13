# Worker bench (P1-2h stage D): the SAME fight and budget, once in one game process and once in a
# second game process. Orchestration is a script on purpose: it must never live in the game hot path.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\worker-bench.ps1
#
# Preferences are snapshotted before the extra process starts and restored after it exits: a second
# game instance writes preferences/ on shutdown, and the player's files must survive that.
[CmdletBinding()]
param(
    [string]$GameDir = 'D:\Steam\steamapps\common\SlayTheSpire',
    [string]$WorkshopDir = 'D:\Steam\steamapps\workshop\content\646570',
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$homeDir = Join-Path $env:USERPROFILE 'sts1-engine-solver'
$prefDir = Join-Path $GameDir 'preferences'
$prefRoot = Join-Path $homeDir 'prefs-backup'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$prefBackup = Join-Path $prefRoot ("bench-" + $stamp)

function Snapshot-Preferences {
    if (-not (Test-Path $prefDir)) { return }
    New-Item -ItemType Directory -Force -Path $prefBackup | Out-Null
    Copy-Item (Join-Path $prefDir '*') $prefBackup -Recurse -Force
    Write-Host "preferences: snapshot -> $prefBackup"
}

function Restore-Preferences {
    if (-not (Test-Path $prefBackup)) { return }
    for ($attempt = 0; $attempt -lt 8; $attempt++) {
        try {
            Copy-Item (Join-Path $prefBackup '*') $prefDir -Recurse -Force
            Write-Host "preferences: restored from snapshot"
            return
        } catch {
            Start-Sleep -Milliseconds 400
        }
    }
    Write-Host "preferences: restore left some files in place (game may still be running)"
}

# 1. baseline: this run also writes the live request for the worker instance.
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run.ps1') -Mode bench-prep -TimeoutSeconds 300 | Out-Host
$baseline = Join-Path $homeDir 'bench-baseline.txt'
$request = Join-Path $homeDir 'autotest-wbench.request'
$result = Join-Path $homeDir 'wbench-result.txt'
if (-not (Test-Path $request)) { throw "bench-prep did not write $request" }
Remove-Item $result -Force -ErrorAction SilentlyContinue

# 2. worker: a second game process with its own instance tag (own request/log files).
Snapshot-Preferences
$java = Join-Path $GameDir 'jre\bin\java.exe'
$mts = Join-Path $WorkshopDir '1605060445\ModTheSpire.jar'
$launchArgs = @('-Dsts1solver.instance=wbench', '-jar', $mts, '--skip-launcher', '--mods', 'sts1-engine-solver,basemod', '--skip-intro')
Write-Host ("worker    : " + ($launchArgs -join ' '))
$wall = Get-Date
$proc = Start-Process -FilePath $java -ArgumentList $launchArgs -WorkingDirectory $GameDir -PassThru
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$done = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 700
    if (Test-Path $result) { $done = $true; break }
}
if (-not $proc.HasExited) {
    try { $proc.Kill() } catch { }
}
Start-Sleep -Milliseconds 800
Restore-Preferences
if (-not $done) { Write-Host 'worker: TIMEOUT (no result file)'; exit 3 }

# 3. report: compare the two results on the same fight/budget.
function Read-Kv([string]$path) {
    $map = @{}
    Get-Content $path | ForEach-Object {
        $i = $_.IndexOf('=')
        if ($i -gt 0) { $map[$_.Substring(0, $i)] = $_.Substring($i + 1) }
    }
    return $map
}
$b = Read-Kv $baseline
$w = Read-Kv $result
Write-Host ('worker wall: ' + [math]::Round(((Get-Date) - $wall).TotalSeconds, 1) + 's (process start -> result file)')
Write-Host ('baseline : win=' + $b['win'] + ' hpLoss=' + $b['hpLoss'] + ' steps=' + $b['steps'] + ' nodes=' + $b['nodes'] + ' ' + $b['millis'] + 'ms (in-process)')
Write-Host ('worker   : win=' + $w['win'] + ' hpLoss=' + $w['hpLoss'] + ' steps=' + $w['steps'] + ' nodes=' + $w['nodes'] + ' reason=' + $w['reason'] + ' mismatch=' + $w['mismatch'])
if ($w['mismatch']) { Write-Host 'worker REFUSED the live state (fingerprint mismatch)'; exit 4 }
Write-Host ('agree    : win=' + ($b['win'] -eq $w['win']) + ' hpLoss=' + ($b['hpLoss'] -eq $w['hpLoss']))
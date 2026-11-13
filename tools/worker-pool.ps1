# Worker pool (P1-2h stage B): N game processes stay warm and pull tasks from a shared queue.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\worker-pool.ps1
#
# Why a pool at all: one game process costs ~10-12 s of JVM + ModTheSpire + engine warm-up before
# it can answer a single request (P1-2h stage D measured that wall). A pool pays that cost once per
# worker and then answers requests in well under a second, which is what makes 2-3 parallel searches
# worth the RAM. The queue is a directory of files (drop-in, claim by atomic rename) so the game hot
# path never blocks on a socket.
#
# Preferences are snapshotted before the extra processes start and restored after they exit: a second
# game instance writes preferences/ on shutdown, and the player's files must survive that.
[CmdletBinding()]
param(
    [string]$GameDir = 'D:\Steam\steamapps\common\SlayTheSpire',
    [string]$WorkshopDir = 'D:\Steam\steamapps\workshop\content\646570',
    [int]$Workers = 2,
    [int]$TasksPerRound = 2,
    [int]$TimeoutSeconds = 300,
    [int]$IdleSeconds = 300
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$homeDir = Join-Path $env:USERPROFILE 'sts1-engine-solver'
$prefDir = Join-Path $GameDir 'preferences'
$prefRoot = Join-Path $homeDir 'prefs-backup'
$pool = Join-Path $homeDir 'pool'
$done = Join-Path $pool 'done'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$prefBackup = Join-Path $prefRoot ('pool-' + $stamp)
$utf8 = New-Object System.Text.UTF8Encoding($false)
$seq = 0

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
            Write-Host 'preferences: restored from snapshot'
            return
        } catch {
            Start-Sleep -Milliseconds 400
        }
    }
    Write-Host 'preferences: restore left some files in place (game may still be running)'
}

function Read-Kv([string]$path) {
    $map = @{}
    Get-Content $path | ForEach-Object {
        $i = $_.IndexOf('=')
        if ($i -gt 0) { $map[$_.Substring(0, $i)] = $_.Substring($i + 1) }
    }
    return $map
}

# The startup request's FIRST line is the mode, so a pool worker is seeded with "worker-pool".
# No BOM: Java reads the file as UTF-8 and a BOM would glue itself onto the mode string.
function Write-StartupRequest([int]$index) {
    $path = Join-Path $homeDir ('autotest-wp' + $index + '.request')
    $text = "worker-pool
idleSeconds=$IdleSeconds
"
    [System.IO.File]::WriteAllText($path, $text, $utf8)
}

# A task is the bench-prep live request with only its result path rewritten; writing to a temp name
# and renaming means a worker can never claim a half-written file.
function Send-Task([string]$template) {
    # $script: is required: a plain $seq++ inside a function writes a call-local copy, which would
    # reuse task-0001 for the second task and trip File.Move's "destination exists".
    $script:seq++
    $n = $script:seq
    $name = 'pool-result-' + $n + '.txt'
    $lines = [System.IO.File]::ReadAllLines($template, [Text.Encoding]::UTF8)
    $kept = @()
    foreach ($line in $lines) {
        if ($line -match '^\s*out\s*=') { continue }
        $kept += $line
    }
    $kept += ('out=' + $name)
    $target = Join-Path $pool ('task-' + $n.ToString('0000') + '.request')
    $tmp = Join-Path $pool ('task-' + $n.ToString('0000') + '.tmp')
    [System.IO.File]::WriteAllText($tmp, (($kept -join "`r`n") + "`r`n"), $utf8)
    if (Test-Path $target) { Remove-Item $target -Force }
    [System.IO.File]::Move($tmp, $target)
    return @{ Name = $name; Path = (Join-Path $pool $name); Sent = (Get-Date); Task = $target }
}

# 1. baseline: this also writes the live request the pool tasks are built from.
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run.ps1') -Mode bench-prep -TimeoutSeconds 300 | Out-Host
$baseline = Join-Path $homeDir 'bench-baseline.txt'
$template = Join-Path $homeDir 'autotest-wbench.request'
if (-not (Test-Path $template)) { throw "bench-prep did not write $template" }

# 2. a clean queue, then the workers.
if (Test-Path $pool) { Get-ChildItem $pool -Force | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $pool | Out-Null
New-Item -ItemType Directory -Force -Path $done | Out-Null
for ($i = 1; $i -le $Workers; $i++) { Write-StartupRequest $i }

Snapshot-Preferences
$java = Join-Path $GameDir 'jre\bin\java.exe'
$mts = Join-Path $WorkshopDir '1605060445\ModTheSpire.jar'
$procs = @()
$cold = @{}
$wall = Get-Date
try {
for ($i = 1; $i -le $Workers; $i++) {
    $launchArgs = @(('-Dsts1solver.instance=wp' + $i), '-jar', $mts, '--skip-launcher', '--mods', 'sts1-engine-solver,basemod', '--skip-intro')
    Write-Host ('worker    : wp' + $i + ' ' + ($launchArgs -join ' '))
    $procs += Start-Process -FilePath $java -ArgumentList $launchArgs -WorkingDirectory $GameDir -PassThru
}
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
foreach ($i in 1..$Workers) {
    $ready = Join-Path $pool ('ready-wp' + $i)
    while (-not (Test-Path $ready) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
    if (-not (Test-Path $ready)) { throw ('wp' + $i + ' never became ready (see solver-wp' + $i + '.log)') }
    $cold['wp' + $i] = [math]::Round(((Get-Date) - $wall).TotalSeconds, 1)
    Write-Host ('ready     : wp' + $i + ' after ' + $cold['wp' + $i] + 's | ' + ((Get-Content $ready) -join ' '))
}

# 3. two rounds: the first two tasks pay for nothing anyway, so the second round is the steady state.
$rows = @()
for ($round = 1; $round -le 2; $round++) {
    $sent = @()
    for ($k = 1; $k -le $TasksPerRound; $k++) { $sent += (Send-Task $template) }
    $roundDeadline = (Get-Date).AddSeconds($TimeoutSeconds)
    foreach ($item in $sent) {
        while (-not (Test-Path $item.Path) -and (Get-Date) -lt $roundDeadline) { Start-Sleep -Milliseconds 100 }
        $latency = [math]::Round(((Get-Date) - $item.Sent).TotalSeconds, 2)
        $state = if (Test-Path $item.Path) { 'ok' } else { 'TIMEOUT' }
        if ($state -ne 'ok') { throw ('round ' + $round + ' task ' + $item.Name + ' produced no result in ' + $TimeoutSeconds + 's') }
        $kv = Read-Kv $item.Path
        $rows += [pscustomobject]@{
            Round = $round; Result = $item.Name; Latency = $latency;
            Win = $kv['win']; HpLoss = $kv['hpLoss']; Steps = $kv['steps']; Nodes = $kv['nodes']
        }
    }
    Write-Host ('round ' + $round + ' : ' + (($sent | ForEach-Object { $_.Name }) -join ', ') + ' done in ' + [math]::Round(((Get-Date) - $sent[0].Sent).TotalSeconds, 2) + 's')
}

} finally {
# 4. stop: a stop file, then each worker exits on its own and leaves a goodbye note. A finally
# block because a failure above must never leave two game processes behind or preferences unrestored.
[System.IO.File]::WriteAllText((Join-Path $pool 'stop'), "pool gate $stamp", $utf8)
$byeDeadline = (Get-Date).AddSeconds(60)
foreach ($i in 1..$Workers) {
    $bye = Join-Path $pool ('bye-wp' + $i)
    while (-not (Test-Path $bye) -and (Get-Date) -lt $byeDeadline) { Start-Sleep -Milliseconds 200 }
    if (Test-Path $bye) { Write-Host ('bye       : ' + ((Get-Content $bye) -join ' ')) }
}
foreach ($proc in $procs) { if (-not $proc.HasExited) { try { $proc.Kill() } catch { } } }
Start-Sleep -Milliseconds 800
Restore-Preferences
}

# 5. report.
$b = Read-Kv $baseline
Write-Host ('baseline  : win=' + $b['win'] + ' hpLoss=' + $b['hpLoss'] + ' steps=' + $b['steps'] + ' nodes=' + $b['nodes'] + ' ' + $b['millis'] + 'ms (in-process)')
$rows | Format-Table -AutoSize | Out-String | Write-Host
$agree = $true
foreach ($row in $rows) {
    if ($row.Win -ne $b['win'] -or $row.HpLoss -ne $b['hpLoss']) { $agree = $false }
}
Write-Host ('agree     : win/hpLoss equal to the in-process baseline = ' + $agree)
Write-Host ('queue     : ' + $done + ' has ' + ((Get-ChildItem $done -ErrorAction SilentlyContinue) | Measure-Object).Count + ' archived task(s)')
Get-ChildItem $homeDir -Filter 'solver-wp*.log' | ForEach-Object {
    $lines = Select-String -Path $_.FullName -Pattern 'pool:|worker: solved' | Select-Object -Last 12
    if ($lines) { Write-Host ('log ' + $_.Name + ':'); $lines | ForEach-Object { Write-Host ('  ' + $_.Line) } }
}

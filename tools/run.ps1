<#
.SYNOPSIS
    Runs the automated evidence suite: launch the game, wait for the mod's own result, report it.

.DESCRIPTION
    The game is a real GUI process (ModTheSpire patches it in place); the mod detects a request file
    at startup, runs the suite at the main menu, writes its verdict to its own log and halts. This
    script is the machine half: it seeds the request, launches, waits for the verdict line, prints
    only this run's log section, and enforces a timeout so a hang is a failure rather than a wait.

.PARAMETER KeepAlive
    Do not kill the game if the suite times out (useful when debugging by hand).
#>
param(
    [string]$GameDir = 'D:\Steam\steamapps\common\SlayTheSpire',
    [string]$WorkshopDir = 'D:\Steam\steamapps\workshop\content\646570',
    [int]$TimeoutSeconds = 180,
    [string]$Mode = '',
    # Extra JVM flags for instrumentation runs, e.g. '-XX:StartFlightRecording=duration=90s,filename=build\run.jfr'
    [string]$JavaArgs = '',
    # Explicit ModTheSpire load list ("ninja.basemod" style: jar base names or mod ids, comma
    # separated). Empty means "the two the mod needs"; -AllMods means "every mod on this machine".
    [string]$Mods = '',
    [switch]$AllMods,
    # m17581: the default run keeps only the core gates (the ones that guard a bug the player met).
    [switch]$Full,
    # m20027: run only these gates ("S119,S116") so proving one small change costs ~40s, not 8 min.
    [string]$Gates = '',
    [switch]$KeepAlive
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$homeDir = Join-Path $env:USERPROFILE 'sts1-engine-solver'
$logPath = Join-Path $homeDir 'solver.log'
$request = Join-Path $homeDir 'autotest.request'
New-Item -ItemType Directory -Force -Path $homeDir | Out-Null
# The debug surface (probe keys + the request file above) is opt-in per machine: the mod reads this
# marker at load and stays a normal, player-facing mod without it. Writing it here keeps every gate
# run working, and a player's install never has it.
New-Item -ItemType File -Force -Path (Join-Path $homeDir 'debug.keys') | Out-Null

# The monitor's power plan turns the display off after three idle minutes on this machine, and a
# sleeping display blocks the game's Present (LWJGL parks in nSwapBuffers) -- a long search then looks
# like a hang: no log line, no CPU, no verdict. Ask Windows to keep the display on for as long as this
# script runs, so "the suite is slow" can never be confused with "the screen went dark".
if (-not ('Sts1KeepAwake' -as [type])) {
    Add-Type -Namespace '' -Name 'Sts1KeepAwake' -MemberDefinition @'
[DllImport("kernel32.dll")]
public static extern uint SetThreadExecutionState(uint esFlags);
'@
}
$ES_CONTINUOUS = [uint32]2147483648
$ES_SYSTEM_REQUIRED = [uint32]0x00000001
$ES_DISPLAY_REQUIRED = [uint32]0x00000002
[void][Sts1KeepAwake]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_DISPLAY_REQUIRED -bor $ES_SYSTEM_REQUIRED)

# A running game holds the jar and would not load the new classes: refuse rather than measure old code.
$running = Get-Process -Name 'java','SlayTheSpire' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path.StartsWith($GameDir) }
if ($running) {
    Write-Host "REFUSING: the game is already running (pid $($running.Id -join ','))."
    exit 2
}

# Since B39 the solver log rotates: the previous session's lines are moved into logs when this run
# starts, and the live file begins again at its header line. So "this run's lines" is an offset that
# must be reset whenever the live file shrinks below what it was before the launch.
$before = 0
$beforeBytes = 0L
if (Test-Path $logPath) {
    $before = (Get-Content $logPath).Count
    $beforeBytes = (Get-Item $logPath).Length
}

# The harness creates synthetic characters and fights at the main menu, and the game writes its
# preference files (STSPlayer, STSData<CLASS>) whenever it saves. That is the PLAYER's profile: a test
# must never touch it. Two real incidents made this explicit: STSDataWatcher was reduced to "{}", and
# STSDataVagabond lost its run statistics because a restore raced the game's still-running save.
#
# Therefore: snapshot to a PERSISTENT folder before the run; restore only after the java process is
# confirmed gone; verify every file's length afterwards; keep the last snapshots for manual recovery.
$prefDir = Join-Path $GameDir 'preferences'
$prefRoot = Join-Path $homeDir 'prefs-backup'
$prefBackup = $null

# This run's lines of the live log, rotation-aware (see $beforeBytes above).
function Get-FreshLog {
    try {
        if (-not (Test-Path $logPath)) { return @() }
        if ((Get-Item $logPath).Length -lt $script:beforeBytes) {
            $script:before = 0
            $script:beforeBytes = 0L
        }
        return @(Get-Content $logPath | Select-Object -Skip $script:before)
    } catch {
        return @()
    }
}

function Wait-GameGone([int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $alive = Get-Process -Name 'java','SlayTheSpire' -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -and $_.Path.StartsWith($GameDir) }
        if (-not $alive) { return $true }
        Start-Sleep -Milliseconds 200
    }
    return $false
}

function Restore-Preferences {
    if (-not $prefBackup -or -not (Test-Path $prefBackup)) { return }
    if (-not (Wait-GameGone 15)) {
        Write-Host "preferences: GAME STILL RUNNING - restore postponed (kept $prefBackup)"
        return
    }
    $snapshot = @(Get-ChildItem -Path $prefBackup -File)
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $bad = 0
        foreach ($f in $snapshot) {
            $target = Join-Path $prefDir $f.Name
            if ((Test-Path $target) -and ((Get-Item $target).Length -eq $f.Length)) { continue }
            try {
                Copy-Item -Path $f.FullName -Destination $target -Force
            } catch {
                $bad++
            }
        }
        if ($bad -eq 0) {
            Write-Host "preferences: restored $($snapshot.Count) file(s) from snapshot"
            break
        }
        Write-Host "preferences: restore attempt $attempt left $bad file(s) bad"
        Start-Sleep -Milliseconds 300
    }
    # keep the snapshot on disk for manual recovery; prune old ones
    $snapshots = @(Get-ChildItem -Path $prefRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    $snapshots | Select-Object -Skip 10 | ForEach-Object {
        Remove-Item -Path $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# If a previous run crashed before restoring, recover first.
if (Test-Path $prefRoot) {
    $pending = @(Get-ChildItem -Path $prefRoot -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'PENDING') } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1)
    if ($pending.Count -gt 0) {
        # B110 (user m05182): a pending snapshot may be OLDER than what the player has since done.
        # Restoring it blindly would roll their save back ("my run was replaced by an older one"),
        # so only restore when nothing in preferences is newer than the snapshot's PENDING marker.
        $marker = Get-Item (Join-Path $pending[0].FullName 'PENDING')
        # The safe default is NOT to restore: a snapshot left by a crashed run may be older than what
        # the player did since, and restoring it would roll their character save back. It is moved
        # aside (kept for inspection) unless the caller explicitly asks for the restore.
        if ($env:STS1_RESTORE_PENDING -ne '1') {
            $aside = Join-Path $pending[0].FullName ('PENDING.stale-' + (Get-Date -Format 'HHmmss'))
            Move-Item -Path $marker.FullName -Destination $aside -Force
            Write-Host ("preferences: left snapshot {0} alone (it may be older than the player's own save); " +
                "set STS1_RESTORE_PENDING=1 to restore it" -f $pending[0].Name)
        } else {
            $prefBackup = $pending[0].FullName
            Write-Host "preferences: restoring a pending snapshot from $($pending[0].Name)"
            Restore-Preferences
            Remove-Item (Join-Path $pending[0].FullName 'PENDING') -Force -ErrorAction SilentlyContinue
            $prefBackup = $null
        }
    }
}

if (Test-Path $prefDir) {
    New-Item -ItemType Directory -Force -Path $prefRoot | Out-Null
    $prefBackup = Join-Path $prefRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
    New-Item -ItemType Directory -Force -Path $prefBackup | Out-Null
    Copy-Item -Path (Join-Path $prefDir '*') -Destination $prefBackup -Recurse -Force
    Set-Content -Path (Join-Path $prefBackup 'PENDING') -Value 'restore pending' -Encoding ascii
    Write-Host "preferences: snapshot -> $prefBackup"
}

# m17581: the short suite is asked for through the request file; a JVM flag would not survive
# ModTheSpire's own re-launch of the game.
$requestMode = $Mode
if ($Gates.Trim().Length -gt 0) {
    $requestMode = 'gates:' + $Gates.Trim()
} elseif ($requestMode.Trim().Length -eq 0 -and -not $Full) { $requestMode = 'core' }
Set-Content -Path $request -Value $requestMode -Encoding ascii

$java = Join-Path $GameDir 'jre\bin\java.exe'
# ModTheSpire is started DIRECTLY with --skip-launcher: the launcher jar opens MTS's mod-selection
# window and waits for a click, which is a hang for an automated run. --skip-intro drops the logo.
$mts = Join-Path $WorkshopDir '1605060445\ModTheSpire.jar'
if (-not (Test-Path $java)) { throw "bundled JRE missing: $java" }
if (-not (Test-Path $mts)) { throw "ModTheSpire jar missing: $mts" }

# --mods FILTERS the load list (it does not sideload), and a filtered launch reports only those mods
# to ModTheSpire -- which is exactly why a "coverage" run once counted 370 vanilla cards and claimed
# to cover the install.
#
# B90 (2026-11-02): -AllMods used to OMIT --mods, believing that "no filter" means "load everything".
# It does not: ModTheSpire then reads its own saved selection (%LOCALAPPDATA%\ModTheSpire\mod_lists.json),
# which is the player's old list -- so a freshly subscribed workshop character was silently absent and
# the all-mods coverage run kept reporting the same 541 cards / 6 characters as the filtered run.
# -AllMods now passes the EXPLICIT list: every discovered jar that really is a mod (contains a
# ModTheSpire.json descriptor), minus the loader itself.
function Get-ModJarNames([string]$dir, [switch]$RecurseChildren) {
    $out = @()
    if (-not (Test-Path $dir)) { return $out }
    $jars = @(Get-ChildItem -Path $dir -Filter *.jar -File -ErrorAction SilentlyContinue)
    if ($RecurseChildren) {
        foreach ($d in @(Get-ChildItem -Path $dir -Directory -ErrorAction SilentlyContinue)) {
            $jars += @(Get-ChildItem -Path $d.FullName -Filter *.jar -File -ErrorAction SilentlyContinue)
        }
    }
    foreach ($j in $jars) {
        $base = $j.BaseName
        if ($base -eq 'ModTheSpire' -or $base -eq 'mts-launcher' -or $base -eq 'mod-uploader') { continue }
        $modId = $null
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
            $zip = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
            try {
                $entry = $zip.GetEntry('ModTheSpire.json')
                if ($null -ne $entry) {
                    $reader = New-Object System.IO.StreamReader($entry.Open())
                    try { $descriptor = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
                    # The descriptor field is "modid" (BaseMod's is "basemod"), not "id".
                    if ($descriptor.modid) { $modId = [string]$descriptor.modid }
                }
            } finally { $zip.Dispose() }
        } catch {
            $modId = $null
        }
        # ModTheSpire's --mods matches mod IDs, NOT jar file names ("ERROR: Unable to find Mod ID
        # 'BaseMod'" for base name BaseMod.jar -- the ID is "basemod"). The descriptor is the only
        # place the ID exists, so read it; fall back to the base name when a jar has none.
        if ($null -ne $modId) { $out += $modId }
    }
    return $out
}
$modFilter = $Mods.Trim()
if ($modFilter.Length -eq 0 -and $AllMods) {
    $discovered = @(Get-ModJarNames (Join-Path $GameDir 'mods')) + @(Get-ModJarNames $WorkshopDir -RecurseChildren)
    $modFilter = (@($discovered | Sort-Object -Unique) -join ',')
    Write-Host ("mods      : " + $discovered.Count + " jar(s) with a ModTheSpire.json -> " + $modFilter)
}
# -Mods REPLACES the default load list, and the default list is the only thing that loads THIS mod.
# Passing just the mods under test therefore launched a game in which nothing ran: no request handling,
# no verdict, and the run only ended at the timeout -- indistinguishable from a hang (2026-11-02, two
# wasted runs). The solver and BaseMod are always loaded, whatever else is asked for.
if ($Mods.Trim().Length -gt 0) {
    $ids = @($Mods -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_.Length -gt 0 })
    if (-not ($ids -contains 'sts1-engine-solver')) { $ids = @('sts1-engine-solver') + $ids }
    if (-not ($ids -contains 'basemod')) { $ids = @('basemod') + $ids }
    $modFilter = (@($ids | Select-Object -Unique) -join ',')
    Write-Host ("mods      : -Mods always keeps sts1-engine-solver + basemod -> " + $modFilter)
}
# JVM flags come from -JavaArgs or, when the caller mangles leading dashes, the environment.
$extraJava = $JavaArgs
if ($extraJava.Trim().Length -eq 0 -and $env:STS1_JAVA_ARGS) { $extraJava = $env:STS1_JAVA_ARGS }
$launchArgs = @()
# m17581: the suite grew to 700+ assertions and six minutes, which buries the bugs it is meant to
# protect. A normal run is core-gates-only; -Full brings the rest back when a change touches them.
if ($extraJava.Trim().Length -gt 0) { $launchArgs += ($extraJava.Trim() -split '\s+') }
$launchArgs += @('-jar', $mts, '--skip-launcher')
if ($modFilter.Length -eq 0) { $modFilter = 'sts1-engine-solver,basemod' }
$launchArgs += @('--mods', $modFilter)
$launchArgs += '--skip-intro'
# Startup timing. Two runs were lost to a silent gap between "launching" and the first log line
# (a stuck ModTheSpire launcher window), and the only way to tell "slow" from "dead" was to wait out
# the whole timeout. Four timestamps turn that into one sentence.
$launchAt = Get-Date
Write-Host ("launching : " + ($launchArgs -join ' '))
$proc = Start-Process -FilePath $java -ArgumentList $launchArgs -WorkingDirectory $GameDir -PassThru

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$done = $false
$exited = $false
$firstLineAt = $null
$requestedAt = $null
$nextBeatAt = $launchAt.AddSeconds(15)
function Elapsed([datetime]$from) {
    if ($null -eq $from) { return 'n/a' }
    return ([int]((New-TimeSpan -Start $launchAt -End $from).TotalSeconds)).ToString() + 's'
}
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 700
    $fresh = @()
    if (Test-Path $logPath) {
        # Only THIS run's lines: a stale 'AUTOTEST DONE' from a previous run (or a stale archive
        # still being read) must never be mistaken for this run finishing.
        $fresh = Get-FreshLog
        if ($null -eq $firstLineAt -and $fresh.Count -gt 0) {
            $firstLineAt = Get-Date
            Write-Host ("first log : +" + (Elapsed $firstLineAt) + " (the mod's classes are loaded)")
        }
        if ($null -eq $requestedAt -and ($fresh -match 'AUTOTEST requested|COVERAGE requested')) {
            $requestedAt = Get-Date
            Write-Host ("requested : +" + (Elapsed $requestedAt) + " (the suite started; before this the game is still booting)")
        }
        $marker = if ($Mode -eq 'coverage') { 'COVERAGE DONE' } else { 'AUTOTEST DONE' }
        if ($fresh -match $marker) { $done = $true; break }
    }
    if ($proc.HasExited) {
        $exited = $true
        Write-Host ("EXITED    : the game ended (exit code " + $proc.ExitCode + ") before the suite reported done")
        break
    }
    if ((Get-Date) -ge $nextBeatAt) {
        $nextBeatAt = (Get-Date).AddSeconds(15)
        $tail = if ($fresh.Count -gt 0) { [string]$fresh[$fresh.Count - 1] } else { '(no log line from this run yet)' }
        if ($tail.Length -gt 110) { $tail = $tail.Substring(0, 110) }
        Write-Host ("waiting   : +" + (Elapsed (Get-Date)) + ", " + $fresh.Count + " line(s) this run, last=" + $tail)
    }
}

if ($done -or $null -ne $firstLineAt) {
    Write-Host ("startup   : first log line +" + (Elapsed $firstLineAt) + ", suite started +" + (Elapsed $requestedAt) + ", finished +" + (Elapsed (Get-Date)))
}

if (-not $done) {
    if ($exited) {
        Write-Host "THE GAME ENDED BEFORE THE SUITE FINISHED (not a timeout; see the last lines above)"
        Restore-Preferences
        exit 5
    }
    if ($null -eq $firstLineAt) {
        Write-Host "STALLED at startup: no log line from the mod after $TimeoutSeconds s - the ModTheSpire launcher window is probably waiting for a click"
    } elseif ($null -eq $requestedAt) {
        Write-Host "STALLED while booting the game: log lines exist but the suite never started (look for an exception in the lines above)"
    } else {
        Write-Host "STALLED inside the suite: it started at +" + (Elapsed $requestedAt) + " but never reported done"
    }
    Write-Host "TIMEOUT after $TimeoutSeconds s waiting for 'AUTOTEST DONE'"
    if (-not $KeepAlive) {
        Get-Process -Name 'java','SlayTheSpire' -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -and $_.Path.StartsWith($GameDir) } | Stop-Process -Force
    }
    Restore-Preferences
exit 3
}

# Print only this run's section of the live log (rotation-aware).
$section = Get-FreshLog
$section | Write-Host

# The mod halts its own JVM when the suite ends; a survivor means it did not, and the next run
# would then measure the old classes.
# A process whose Path cannot be read (no permission, exiting) used to make the Where-Object block
# throw InvokeMethodOnNull on stderr. Read the path defensively and only then decide.
$survivors = @(Get-Process -Name java -ErrorAction SilentlyContinue)
foreach ($p in $survivors) {
    $javaPath = $null
    try { $javaPath = $p.Path } catch { $javaPath = $null }
    if ($javaPath -and $javaPath.StartsWith($GameDir)) {
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    }
}

if ($Mode -eq 'coverage') {
    if ($section -match 'COVERAGE DONE') { Write-Host "COVERAGE FINISHED"; exit 0 }
    Write-Host "NO COVERAGE VERDICT"
    exit 4
}
$verdicts = @($section | Select-String -Pattern 'AUTOTEST RESULT passed=(\d+) failed=(\d+)')
if ($verdicts.Count -eq 0) { Write-Host "NO VERDICT LINE"; exit 4 }
$verdict = $verdicts[$verdicts.Count - 1]
$failed = $verdict.Matches[0].Groups[2].Value
if ($failed -ne '0') { Write-Host "AUTOTEST FAILED (failed=$failed)"; exit 1 }
Write-Host "AUTOTEST PASSED"
Restore-Preferences
exit 0
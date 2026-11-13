# Launch Slay the Spire through ModTheSpire with a chosen mod set and heap,
# wait until the main menu appears, then report the live heap from the GC log.
# Usage: powershell -File tools\gc-ab.ps1 -Mods basemod -Xmx 6g -Tag base
param(
  [string]$Mods = 'basemod',
  [string]$Xmx = '6g',
  [string]$Tag = 'run',
  [int]$WaitSeconds = 150
)
$ErrorActionPreference = 'Stop'
$Game = 'D:\Steam\steamapps\common\SlayTheSpire'
$Mts  = 'D:\Steam\steamapps\workshop\content\646570\1605060445\ModTheSpire.jar'
$Out  = 'C:\Users\YOKI\sts1-engine-solver\gc'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Console = Join-Path $Out ('ab-' + $Tag + '-console.log')
$GcLog   = Join-Path $Out ('ab-' + $Tag + '-gc.log')
Remove-Item $Console,$GcLog -ErrorAction SilentlyContinue
$GameLog = Join-Path $Game 'sendToDevs\logs\SlayTheSpire.log'
$before = if (Test-Path $GameLog) { (Get-Item $GameLog).Length } else { 0 }
$args = @(
  "-Xmx$Xmx",
  '-XX:+UseG1GC',
  '-XX:MaxGCPauseMillis=150',
  '-XX:+PrintGCDetails',
  '-XX:+PrintGCTimeStamps',
  "-Xloggc:$GcLog",
  '-jar', $Mts,
  '--skip-launcher',
  '--mods', $Mods
)
$p = Start-Process -FilePath (Join-Path $Game 'jre\bin\java.exe') -ArgumentList $args -WorkingDirectory $Game -RedirectStandardOutput $Console -RedirectStandardError ($Console + '.err') -PassThru
$menu = $false
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Milliseconds 1500
  if ($p.HasExited) { break }
  if (Test-Path $GameLog) {
    $fs = [System.IO.File]::Open($GameLog,'Open','Read','ReadWrite')
    try {
      $fs.Seek($before,'Begin') | Out-Null
      $sr = New-Object System.IO.StreamReader($fs)
      $tail = $sr.ReadToEnd()
    } finally { $fs.Dispose() }
    if ($tail -match 'Loading character stats') { $menu = $true; break }
  }
}
Start-Sleep -Seconds 6
if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 4
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
$oom = if (Test-Path $Console) { (Select-String -Path $Console -Pattern 'OutOfMemoryError' -Quiet) } else { $false }
$live = ''
if (Test-Path $GcLog) {
  $hits = Select-String -Path $GcLog -Pattern 'Heap after GC invocations' | Select-Object -Last 1
  if ($hits) { $live = ($hits.Line -replace '.*used ([0-9]+[MK]).*','$1') }
}
$heapLine = ''
if (Test-Path $GcLog) {
  $h = Select-String -Path $GcLog -Pattern 'Heap: ' | Select-Object -Last 1
  if ($h) { $heapLine = ($h.Line -replace '^\s+','') }
}
$size = if (Test-Path $GcLog) { (Get-Item $GcLog).Length } else { 0 }
Write-Output ('RESULT tag=' + $Tag + ' mods=' + $Mods + ' xmx=' + $Xmx + ' menu=' + $menu + ' oom=' + $oom + ' liveAfterGc=' + $live + ' gcLogBytes=' + $size + ' last=' + $heapLine)
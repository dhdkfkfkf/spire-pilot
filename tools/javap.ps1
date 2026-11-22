# Dumps game classes with javap into research\javap\<Class>.txt (UTF-8).
#   powershell -File tools\javap.ps1 com.megacrit.cardcrawl.rooms.MonsterRoom
param([Parameter(Mandatory=$true, ValueFromRemainingArguments=$true)][string[]]$Classes)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$userJdks = Join-Path $env:USERPROFILE '.jdks'
if (Test-Path $userJdks) {
    $candidates += Get-ChildItem $userJdks -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object { $_.FullName }
}
$candidates += @(
    'C:\Program Files\Java\jdk1.8.0_291'
    'C:\Program Files\Eclipse Adoptium\jdk-8'
)
$jdk = $candidates | Where-Object { Test-Path (Join-Path $_ 'bin\javap.exe') } | Select-Object -First 1
if (-not $jdk) { throw "No JDK found. Set JAVA_HOME, or put one under $env:USERPROFILE\.jdks." }
$javap = Join-Path $jdk 'bin\javap.exe'
$gameJar = 'D:\Steam\steamapps\common\SlayTheSpire\desktop-1.0.jar'
$mtsJar = 'D:\Steam\steamapps\workshop\content\646570\1605060445\ModTheSpire.jar'
$bmJar = 'D:\Steam\steamapps\workshop\content\646570\1605833019\BaseMod.jar'
$cp = "$gameJar;$mtsJar;$bmJar"
$outDir = Join-Path $root 'research\javap'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
foreach ($c in $Classes) {
    $safe = ($c -replace '[^A-Za-z0-9_]', '_')
    $out = Join-Path $outDir "$safe.txt"
    # Redirect through a variable and write UTF-8: javap's stdout is UTF-8 already, but a raw
    # '*> $file' redirect under PowerShell 5.1 stores UTF-16 and every downstream reader chokes.
    $text = & $javap -p -c -constants -classpath $cp $c 2>&1 | Out-String
    [System.IO.File]::WriteAllText($out, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "$c -> $out ($((Get-Item $out).Length) bytes)"
}

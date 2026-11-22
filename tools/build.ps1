<#
.SYNOPSIS
    Builds the mod jar: compiles against the real game and mod-loader jars and packages it.

.DESCRIPTION
    The game runs on its bundled Java 8 JRE, so the output must be Java 8 bytecode even though the
    compiler on this machine is newer; that is why --release 8 is always passed.

    A JDK is required (the game only ships a JRE). Set -Jdk or JAVA_HOME if auto-detection fails.

.EXAMPLE
    powershell -NoProfile -File tools\build.ps1
#>
[CmdletBinding()]
param(
    [string]$Jdk,
    [string]$GameDir = 'D:\Steam\steamapps\common\SlayTheSpire',
    [string]$WorkshopDir = 'D:\Steam\steamapps\workshop\content\646570',
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$srcDir = Join-Path $root 'src\main\java'
$resDir = Join-Path $root 'resources'
$buildDir = Join-Path $root 'build'
$modDir = Join-Path $root 'mod'
$jarName = 'spire-pilot.jar'

# Each build gets its own directory. javac does not delete the .class of a deleted source, so a
# reused classes directory ships code whose source no longer exists; a per-run directory cannot.
$runStamp = "$PID-" + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$runDir = Join-Path $buildDir ("runs\build-" + $runStamp)
$classesDir = Join-Path $runDir 'classes'
$stagedJar = Join-Path $runDir $jarName

function Resolve-Jdk {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    # A JDK staged under the user profile survives tool/temp cleanup. The migration moved the only
    # Java 8 JDK here from the old Trae temp directory.
    $userJdks = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $userJdks) {
        $candidates += Get-ChildItem $userJdks -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $_.FullName }
    }
    $candidates += @(
        'F:\Stata\utilities\java\windows-x64\zulu-jdk21.0.11'
        'F:\PyCharm 2026.2.1\jbr'
        'C:\Program Files\Eclipse Adoptium\jdk-8'
        'C:\Program Files\Java\jdk1.8.0_291'
    )
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $javac = Join-Path $c 'bin\javac.exe'
        if (Test-Path $javac) { return $c }
    }
    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) { return (Split-Path (Split-Path $onPath.Source -Parent) -Parent) }
    throw "No JDK found. Install one and pass -Jdk <path>, or set JAVA_HOME."
}

$jdkHome = Resolve-Jdk -Explicit $Jdk
$javac = Join-Path $jdkHome 'bin\javac.exe'
# javac writes "-version" to stderr, and under 'Stop' a native command's stderr becomes a
# terminating error record (the reference project hit this). Going through cmd keeps the version on
# stdout, so the probe cannot fail the build.
$versionText = [string](cmd /c "`"$javac`" -version 2>&1")
$major = 0
if ($versionText -match 'javac\s+(\d+)') { $major = [int]$Matches[1] }
if ($major -ge 9) { $targetArgs = @('--release', '8') } else { $targetArgs = @('-source', '8', '-target', '8') }
Write-Host "JDK       : $jdkHome"
Write-Host "javac     : $($versionText.Trim())  -> $($targetArgs -join ' ')"

$gameJar = Join-Path $GameDir 'desktop-1.0.jar'
$mtsJar = Join-Path $WorkshopDir '1605060445\ModTheSpire.jar'
$baseModJar = Join-Path $WorkshopDir '1605833019\BaseMod.jar'
$stslibJar = Join-Path $WorkshopDir '1609158507\StSLib.jar'
$classpath = @($gameJar, $mtsJar, $baseModJar, $stslibJar)
foreach ($entry in $classpath) {
    if (-not (Test-Path $entry)) { throw "Missing dependency: $entry" }
}
$cpArg = ($classpath -join ';')

if ($Clean) {
    foreach ($d in @($buildDir, $modDir)) {
        if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    }
}
New-Item -ItemType Directory -Force -Path $classesDir, $modDir | Out-Null

try {
    $sources = Get-ChildItem $srcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
    if (-not $sources) { throw "No sources found under $srcDir" }
    Write-Host "Compiling : $($sources.Count) source file(s)"

    # javac reads an argument file: Windows command lines have a length limit and this tree is large.
    $argFile = Join-Path $runDir 'sources.txt'
    Set-Content -Path $argFile -Value ($sources | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }) -Encoding ascii

    # JUDGE ONLY javac's exit code. Under 'Stop', a native command's stderr becomes a terminating
    # error, and javac writes deprecation NOTES there -- a successful build then reports failure.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # -J keeps javac's own messages in English: a localized message decoded by a different code
        # page becomes mojibake, and mojibake in a tool log poisons every later read of it.
        $javacOutput = & $javac '-J-Duser.language=en' '-J-Duser.country=US' @targetArgs '-Xlint:-options' '-encoding' 'UTF-8' '-cp' $cpArg '-d' $classesDir "@$argFile" 2>&1
        $javacExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
    foreach ($line in $javacOutput) { Write-Host ([string]$line) }
    if ($javacExit -ne 0) { throw "javac failed with exit code $javacExit" }

    # EVERY resource, not just the manifest: the leak inventory is a data file the mod loads from
    # its own jar, and a jar without it silently reports "0 fields checked" -- a false green.
    Copy-Item (Join-Path $resDir '*') $classesDir -Recurse -Force
    $stamp = @(
        "build.utc=$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))"
        "build.pid=$PID"
    )
    Set-Content -Path (Join-Path $classesDir 'sts1solver-build.txt') -Value $stamp -Encoding ascii
    # The version is read at runtime from ONE place: ModTheSpire.json. A separate generated copy sits
    # in the jar because many mods ship a root ModTheSpire.json and getResourceAsStream("/ModTheSpire.json")
    # returns whichever jar the classloader reaches first -- the 1.0.0 report bundle printed another
    # mod's "5.56.0" for exactly that reason.
    $mtsVersion = (Get-Content (Join-Path $resDir 'ModTheSpire.json') -Raw | ConvertFrom-Json).version
    Set-Content -Path (Join-Path $classesDir 'spire-pilot.version') -Value $mtsVersion -Encoding ascii -NoNewline

    # ZipArchive writes the entries ourselves: CreateFromDirectory on Windows PowerShell stores
    # backslashes, which the JVM does not treat as a package separator, and the mod then "does not
    # exist" at runtime.
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path $stagedJar) { Remove-Item $stagedJar -Force }
    $zip = [System.IO.Compression.ZipFile]::Open($stagedJar, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $prefixLen = $classesDir.TrimEnd('\').Length + 1
        foreach ($file in Get-ChildItem $classesDir -Recurse -File) {
            $entryName = $file.FullName.Substring($prefixLen).Replace('\', '/')
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $file.FullName, $entryName,
                [System.IO.Compression.CompressionLevel]::Optimal)
        }
    } finally {
        $zip.Dispose()
    }

    # A jar whose entries use backslashes loads as "mod not found", so check before publishing.
    $check = [System.IO.Compression.ZipFile]::OpenRead($stagedJar)
    try {
        $names = @($check.Entries | ForEach-Object { $_.FullName })
    } finally {
        $check.Dispose()
    }
    if ($names -contains 'ModTheSpire.json' -and ($names -contains 'sts1solver/SolverMod.class')) {
        Write-Host "Jar       : entries look correct ($($names.Count) entries)"
    } else {
        throw "jar is missing ModTheSpire.json or sts1solver/SolverMod.class"
    }

    $finalJar = Join-Path $modDir $jarName
    Move-Item -LiteralPath $stagedJar -Destination $finalJar -Force
    $size = (Get-Item $finalJar).Length
    Write-Host "Built     : $finalJar ($size bytes)"
    Write-Host "BUILD OK"
} finally {
    if (Test-Path $runDir) {
        Remove-Item $runDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}
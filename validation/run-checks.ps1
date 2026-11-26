param(
    [string]$Tv,
    [switch]$Live,
    [switch]$LiveLyrics
)
$ErrorActionPreference = 'Stop'
$release = Split-Path -Parent $PSScriptRoot
. (Join-Path $release 'scripts\env.ps1')

$tc = Resolve-LiquidToolchain -RepoRoot $release
if (-not $Tv) { $Tv = $env:TV_ADDR }
if (-not $Tv) { throw 'Pass -Tv <host:port> or set TV_ADDR in .env.' }

$classes = Join-Path $PSScriptRoot 'classes'
$dex = Join-Path $PSScriptRoot 'dex'
New-Item -ItemType Directory -Force $classes, $dex | Out-Null

$loader = Join-Path $release 'build\loader.jar'
$stubsJar = Join-Path $release 'build\stubs.jar'
$stubsDir = Join-Path $release 'build\stubs'
$bridge = Join-Path $release 'bridge\bridge.jar'
if (-not (Test-Path -LiteralPath $loader)) { throw 'Missing build\loader.jar — run build.ps1 -CompileOnly first.' }
if (-not (Test-Path -LiteralPath $bridge)) { throw 'Missing bridge\bridge.jar — run bridge\build.ps1 first.' }
if (-not (Test-Path -LiteralPath $stubsJar)) {
    if (-not (Test-Path -LiteralPath $stubsDir)) { throw 'Missing build\stubs — run build.ps1 -CompileOnly first.' }
    & $tc.Jar cf $stubsJar -C $stubsDir .
    if ($LASTEXITCODE -ne 0) { throw 'Stubs jar packaging failed' }
}

$readingLibraries = @(Get-ChildItem (Join-Path $release 'libs') -Filter '*.jar' | ForEach-Object FullName)
$readingClasspath = $readingLibraries -join ';'

$names = @(
    'CheckRunner', 'MetadataCheck', 'MultiMediaCheck', 'DirectMediaCheck', 'GenericMediaCheck',
    'BitChordLyricsCheck', 'Fix140LyricsCheck', 'Fix141Check', 'Fix142Check', 'Fix143VisualizerCheck',
    'Fix144AccuracyCheck', 'Fix145OptimizeCheck', 'Fix146ImproveCheck', 'Fix147PlayClipCheck', 'LiveParallelLyricsCheck', 'GalleryCheck',
    'SpotifyIdCheck', 'LiveMediaCheck', 'SourceChainCheck', 'ExtraSourcesCheck', 'RecordingCheck',
    'AggregateCheck', 'LatencyCheck', 'ProgressStateCheck', 'ProviderFormatCheck', 'CandidateCheck',
    'ManualLyricsCheck', 'RomajiCheck', 'KaraokeCheck', 'RubyLayoutCheck',
    'PersistenceAndRecentCheck', 'PerfAndPopupCheck'
)
$sources = @($names | ForEach-Object { Join-Path $PSScriptRoot "$_.java" })

& $tc.Javac -encoding UTF-8 -source 8 -target 8 -classpath "$($tc.AndroidJar);$stubsJar;$loader;$bridge;$readingClasspath" -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Check compilation failed' }

$testJar = Join-Path $PSScriptRoot 'checks.jar'
& $tc.Jar cf $testJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Check jar packaging failed' }

$checkArchive = Join-Path $dex 'checks-bundle.jar'
if (Test-Path -LiteralPath $checkArchive) { Remove-Item -LiteralPath $checkArchive -Force }
& "$($tc.BuildTools)\d8.bat" --release --min-api 26 --lib $tc.AndroidJar --output $checkArchive $testJar $stubsJar $loader @readingLibraries
if ($LASTEXITCODE -ne 0) { throw 'Check dex packaging failed' }

& python (Join-Path $release 'package-reading.py') 'validation/dex/checks-bundle.jar'
if ($LASTEXITCODE -ne 0) { throw 'Dictionary packaging into check archive failed' }

$bridgeDex = Join-Path $release 'bridge\dex\classes.dex'
$remoteCheckJar = '/data/local/tmp/liquid-check-bundle.jar'
$remoteBridgeDex = '/data/local/tmp/liquid-check-bridge.dex'

& $tc.Adb connect $Tv | Out-Null
try {
    & $tc.Adb -s $Tv push $checkArchive $remoteCheckJar
    if ($LASTEXITCODE -ne 0) { throw 'Check archive push failed' }
    & $tc.Adb -s $Tv push $bridgeDex $remoteBridgeDex
    if ($LASTEXITCODE -ne 0) { throw 'Bridge dex push failed' }

    $checks = @(
        'local.launcherfix.Fix147PlayClipCheck',
        'local.launcherfix.Fix146ImproveCheck',
        'local.launcherfix.Fix145OptimizeCheck',
        'local.launcherfix.Fix144AccuracyCheck',
        'local.launcherfix.Fix143VisualizerCheck',
        'local.launcherfix.Fix142Check',
        'local.launcherfix.Fix141Check',
        'local.launcherfix.Fix140LyricsCheck',
        'local.launcherfix.BitChordLyricsCheck',
        'local.launcherfix.GenericMediaCheck',
        'local.launcherfix.DirectMediaCheck',
        'local.launcherfix.MetadataCheck',
        'local.launcherfix.MultiMediaCheck',
        'local.launcherfix.GalleryCheck',
        'local.launcherbridge.SpotifyIdCheck',
        'local.launcherfix.SourceChainCheck',
        'local.launcherfix.ExtraSourcesCheck',
        'local.launcherfix.RecordingCheck',
        'local.launcherfix.AggregateCheck',
        'local.launcherfix.LatencyCheck',
        'local.launcherfix.ProgressStateCheck',
        'local.launcherfix.ProviderFormatCheck',
        'local.launcherfix.CandidateCheck',
        'local.launcherfix.ManualLyricsCheck',
        'local.launcherfix.RomajiCheck',
        'local.launcherfix.KaraokeCheck',
        'local.launcherfix.RubyLayoutCheck',
        'local.launcherfix.PersistenceAndRecentCheck',
        'local.launcherfix.PerfAndPopupCheck'
    )
    if ($Live) { $checks += 'local.launcherbridge.LiveMediaCheck' }
    if ($LiveLyrics) { $checks += 'local.launcherfix.LiveParallelLyricsCheck' }

    foreach ($check in $checks) {
        $output = & $tc.Adb -s $Tv shell "CLASSPATH=${remoteCheckJar}:${remoteBridgeDex} app_process /system/bin local.launcherfix.CheckRunner $check" 2>&1
        $output | Set-Content -Encoding utf8 (Join-Path $PSScriptRoot (($check.Split('.')[-1]) + '-result.txt'))
        $output
        if ($LASTEXITCODE -ne 0) { throw "$check failed" }
    }
} finally {
    # Keep device /data/local/tmp clean after test runs
    & $tc.Adb -s $Tv shell "rm -f $remoteCheckJar $remoteBridgeDex" | Out-Null
}

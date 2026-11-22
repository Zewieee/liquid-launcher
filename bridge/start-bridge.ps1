param([string]$Tv)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $repoRoot 'scripts\env.ps1')

$tc = Resolve-LiquidToolchain -RepoRoot $repoRoot
if (-not $Tv) { $Tv = $env:TV_ADDR }
if (-not $Tv) { throw 'Pass -Tv <host:port> or set TV_ADDR in .env.' }

$bridgeDex = Join-Path $PSScriptRoot 'dex\classes.dex'
if (-not (Test-Path -LiteralPath $bridgeDex)) { throw 'Build the media bridge first (bridge\build.ps1).' }

$bridgeExisting = (& $tc.Adb -s $Tv shell pidof liquid_spotify_bridge) -join ''
if ($bridgeExisting.Trim()) {
    Write-Output 'SPOTIFY_BRIDGE_ALREADY_RUNNING; stop it first when upgrading the helper'
    exit 0
}

& $tc.Adb -s $Tv push $bridgeDex /data/local/tmp/liquid-spotify-bridge.dex
if ($LASTEXITCODE -ne 0) { throw 'Cannot copy bridge to TV' }

& $tc.Adb -s $Tv shell 'if pidof liquid_spotify_bridge >/dev/null; then echo SPOTIFY_BRIDGE_ALREADY_RUNNING; else CLASSPATH=/data/local/tmp/liquid-spotify-bridge.dex nohup app_process /system/bin --nice-name=liquid_spotify_bridge local.launcherbridge.SpotifyMediaBridge </dev/null >/data/local/tmp/liquid-spotify-bridge.log 2>&1 & fi'
if ($LASTEXITCODE -ne 0) { throw 'Cannot start media bridge' }

$bridgeLog = ''
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Start-Sleep -Milliseconds 500
    $bridgeLog = (& $tc.Adb -s $Tv shell cat /data/local/tmp/liquid-spotify-bridge.log) -join "`n"
    if ($bridgeLog -match 'SPOTIFY_BRIDGE_READY') {
        Write-Output $bridgeLog
        exit 0
    }
}
throw "Media bridge is not connected yet: $bridgeLog"

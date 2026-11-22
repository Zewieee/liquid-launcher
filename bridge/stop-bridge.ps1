param([string]$Tv)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $repoRoot 'scripts\env.ps1')

$tc = Resolve-LiquidToolchain -RepoRoot $repoRoot
if (-not $Tv) { $Tv = $env:TV_ADDR }
if (-not $Tv) { throw 'Pass -Tv <host:port> or set TV_ADDR in .env.' }

$bridgeProcess = ((& $tc.Adb -s $Tv shell pidof liquid_spotify_bridge) -join '').Trim()
if (-not $bridgeProcess) {
    Write-Output 'Media bridge is already stopped'
    exit 0
}
if ($bridgeProcess -notmatch '^\d+$') { throw 'Unexpected bridge process list' }

$bridgeCommand = (& $tc.Adb -s $Tv shell cat "/proc/$bridgeProcess/cmdline") -join ''
if ($bridgeCommand -notlike '*liquid_spotify_bridge*') { throw 'Process identity differs' }

& $tc.Adb -s $Tv shell kill $bridgeProcess
if ($LASTEXITCODE -ne 0) { throw 'Could not stop media bridge' }
Write-Output 'Media information bridge stopped; playback unchanged'

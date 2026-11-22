$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $repoRoot 'scripts\env.ps1')

$tc = Resolve-LiquidToolchain -RepoRoot $repoRoot
New-Item -ItemType Directory -Force classes, dex | Out-Null

& $tc.Javac -encoding UTF-8 -source 8 -target 8 -classpath $tc.AndroidJar -d classes SpotifyMediaBridge.java ..\src\local\launcherfix\MediaSources.java ..\src\local\launcherfix\YouTubeTrack.java
if ($LASTEXITCODE -ne 0) { throw 'Bridge compile failed' }

& $tc.Jar cf bridge.jar -C classes .
if ($LASTEXITCODE -ne 0) { throw 'Bridge jar failed' }

& "$($tc.BuildTools)\d8.bat" --release --min-api 26 --lib $tc.AndroidJar --output dex bridge.jar
if ($LASTEXITCODE -ne 0) { throw 'Bridge D8 failed' }

Write-Output 'SPOTIFY_BRIDGE_DEX_READY'

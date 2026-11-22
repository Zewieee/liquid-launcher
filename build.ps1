param(
    [switch]$CompileOnly,
    [switch]$SkipNative,
    [string]$OutApk = 'dist\LiquidLauncher.apk'
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
. (Join-Path $PSScriptRoot 'scripts\env.ps1')

$tc = Resolve-LiquidToolchain -RepoRoot $PSScriptRoot
function Check { if ($LASTEXITCODE -ne 0) { throw 'Build failed' } }

# Verify pinned Kuromoji & SnakeYAML libraries (run scripts\fetch-libs.ps1 first if missing)
& python package-reading.py
Check
$readingLibraries = @(Get-ChildItem libs -Filter '*.jar' | ForEach-Object FullName)
$readingClasspath = $readingLibraries -join ';'

$baseWork = if ($env:BASE_WORK_DIR) { $env:BASE_WORK_DIR } else { Join-Path $PSScriptRoot 'work' }
if (-not [IO.Path]::IsPathRooted($baseWork)) {
    $baseWork = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $baseWork))
}

if (-not $SkipNative) {
    $jniOut = if ($CompileOnly) { Join-Path $PSScriptRoot 'build\jni-out' } else { Join-Path $baseWork 'lib' }
    & (Join-Path $PSScriptRoot 'native\build.ps1') -Sdk $tc.Sdk -OutputDir $jniOut
    Check
}

foreach ($relative in @('build\stubs', 'build\production', 'build\dex')) {
    $target = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $relative))
    $allowed = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'build')) + [IO.Path]::DirectorySeparatorChar
    if (-not $target.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Generated path escapes build' }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
New-Item -ItemType Directory -Force build\tmp, build\stubs, build\production, build\dex, dist | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$PSScriptRoot\build\tmp"

$stubs = @(Get-ChildItem stubs, stubs-extra -Recurse -Filter '*.java' | ForEach-Object FullName)
$sources = @(Get-ChildItem src -Recurse -Filter '*.java' | ForEach-Object FullName)

& $tc.Javac -encoding UTF-8 -source 8 -target 8 -classpath $tc.AndroidJar -d build\stubs @stubs
Check
& $tc.Javac -encoding UTF-8 -source 8 -target 8 -classpath "$($tc.AndroidJar);build\stubs;$readingClasspath" -d build\production @sources
Check
& $tc.Jar cf build\loader.jar -C build\production .
Check
& "$($tc.BuildTools)\d8.bat" --release --min-api 26 --lib $tc.AndroidJar --classpath build\stubs --output build\dex build\loader.jar @readingLibraries
Check

if ($CompileOnly) {
    Write-Output 'COMPILE_ONLY_OK (build\loader.jar + build\dex\classes.dex ready)'
    exit 0
}

# Full APK packaging & signing (requires locally provided base work dir, apktool, and keystore)
$apktoolJar = $env:APKTOOL_JAR
$apktoolFw = $env:APKTOOL_FRAMEWORK_DIR
if (-not $apktoolJar -or -not (Test-Path -LiteralPath $apktoolJar)) {
    throw 'APKTOOL_JAR not configured or not found. Set APKTOOL_JAR in .env (or pass -CompileOnly).'
}
if (-not (Test-Path -LiteralPath $baseWork)) {
    throw "BASE_WORK_DIR not found: $baseWork. Supply your local decompiled base APK directory or pass -CompileOnly."
}

$keystorePath = $env:KEYSTORE_PATH
$keystoreAlias = $env:KEYSTORE_ALIAS
if (-not $keystorePath -or -not (Test-Path -LiteralPath $keystorePath)) {
    throw 'KEYSTORE_PATH not configured or not found. Set KEYSTORE_PATH in .env (keep keystore outside this repo).'
}
if (-not $keystoreAlias) { throw 'KEYSTORE_ALIAS not configured in .env.' }
if (-not $env:KEYSTORE_PASS) { throw 'KEYSTORE_PASS not configured in .env.' }
if (-not $env:KEY_PASS) { $env:KEY_PASS = $env:KEYSTORE_PASS }

$apktoolArgs = @('-jar', $apktoolJar, 'b', '-f')
if ($apktoolFw -and (Test-Path -LiteralPath $apktoolFw)) {
    $apktoolArgs += @('-p', $apktoolFw)
}
$apktoolArgs += @('--aapt', "$($tc.BuildTools)\aapt2.exe", '-o', 'build\unsigned.apk', $baseWork)
& $tc.Java @apktoolArgs
Check

& python -c "import zipfile; z=zipfile.ZipFile('build/unsigned.apk','a'); z.write('build/dex/classes.dex','classes2.dex',compress_type=zipfile.ZIP_DEFLATED); z.close()"
Check
& python package-reading.py build/unsigned.apk
Check

& "$($tc.BuildTools)\zipalign.exe" -f -P 16 4 build\unsigned.apk build\aligned.apk
Check
& "$($tc.BuildTools)\zipalign.exe" -c -P 16 4 build\aligned.apk
Check

# Pass keystore secrets via env: variables so passwords never appear in process command lines.
& "$($tc.BuildTools)\apksigner.bat" sign --ks $keystorePath --ks-key-alias $keystoreAlias --ks-pass env:KEYSTORE_PASS --key-pass env:KEY_PASS --out $OutApk build\aligned.apk
Check

$certificate = & "$($tc.BuildTools)\apksigner.bat" verify --print-certs $OutApk
Check
if ($env:KEYSTORE_CERT_SHA256) {
    $expectedCert = $env:KEYSTORE_CERT_SHA256.Trim().ToLowerInvariant()
    if (($certificate -join "`n").ToLowerInvariant() -notmatch [regex]::Escape($expectedCert)) {
        throw 'Signing certificate SHA-256 differs from KEYSTORE_CERT_SHA256'
    }
}

Write-Output "APK_READY: $OutApk"

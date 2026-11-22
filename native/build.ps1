param(
    [string]$Sdk,
    [string]$OutputDir
)
$ErrorActionPreference = 'Stop'
$release = Split-Path -Parent $PSScriptRoot
. (Join-Path $release 'scripts\env.ps1')
Import-LiquidEnv -RepoRoot $release

if (-not $Sdk) {
    $Sdk = $env:ANDROID_HOME
    if (-not $Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
    if (-not $Sdk -and $env:LOCALAPPDATA) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
}
if (-not $Sdk -or -not (Test-Path -LiteralPath $Sdk)) {
    throw 'Android SDK not found. Pass -Sdk or set ANDROID_HOME in .env.'
}
if (-not $OutputDir) {
    $OutputDir = Join-Path $release 'build\jni-out'
}

$cargo = (Get-Command cargo -ErrorAction Stop).Source
$ndkVer = if ($env:ANDROID_NDK_VERSION) { $env:ANDROID_NDK_VERSION } else { '27.2.12479018' }
$ndk = Join-Path $Sdk "ndk\$ndkVer"
$clang = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
$readelf = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
if (-not (Test-Path -LiteralPath $clang)) {
    throw "Install Android NDK $ndkVer before building Rust ($clang not found)"
}

$env:CARGO_TARGET_DIR = Join-Path $release 'build\rust'
$env:CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER = $clang
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang

$targets = @(
    @{ Rust = 'armv7-linux-androideabi'; Abi = 'armeabi-v7a'; Clang = 'armv7a-linux-androideabi26' },
    @{ Rust = 'aarch64-linux-android';   Abi = 'arm64-v8a';   Clang = 'aarch64-linux-android26' }
)

foreach ($target in $targets) {
    $objectDir = Join-Path $release "build\jni\$($target.Abi)"
    New-Item -ItemType Directory -Force -Path $objectDir | Out-Null
    $object = Join-Path $objectDir 'pixels_jni.o'
    & $clang "--target=$($target.Clang)" -O2 -fPIC -Wall -Wextra -Werror -c (Join-Path $PSScriptRoot 'src\pixels_jni.c') -o $object
    if ($LASTEXITCODE -ne 0) { throw 'JNI adapter compilation failed' }

    & $cargo +1.85.1 rustc --release --locked --manifest-path (Join-Path $PSScriptRoot 'Cargo.toml') --target $target.Rust -- -C "link-arg=--target=$($target.Clang)" -C 'link-arg=-Wl,-z,max-page-size=16384' -C "link-arg=$object"
    if ($LASTEXITCODE -ne 0) { throw "Rust build failed for $($target.Abi)" }

    $library = Join-Path $env:CARGO_TARGET_DIR "$($target.Rust)\release\libliquid_blur.so"
    $symbols = & $readelf --dyn-syms --wide $library
    if ($LASTEXITCODE -ne 0) { throw 'Native symbol inspection failed' }

    foreach ($name in @('probeNative', 'blurNative', 'trimNative')) {
        if (($symbols -join "`n") -notmatch "Java_local_launcherfix_RustBlur_$name") { throw "JNI export missing: $name" }
    }
    foreach ($name in @('recolourNative', 'lensNative')) {
        if (($symbols -join "`n") -notmatch "Java_local_launcherfix_RustPixels_$name") { throw "JNI export missing: $name" }
    }
    if (($symbols -join "`n") -notmatch 'Java_local_launcherfix_RustVideo_drawNative') { throw 'Video JNI export missing' }

    $dynamic = & $readelf --dynamic $library
    if ($LASTEXITCODE -ne 0) { throw 'Native dependency inspection failed' }
    foreach ($line in $dynamic) {
        if ($line -match '\(NEEDED\).*\[([^\]]+)\]') {
            if ($Matches[1] -notin @('libc.so', 'libdl.so', 'libm.so', 'liblog.so', 'libjnigraphics.so', 'libGLESv2.so')) {
                throw "Unexpected native dependency: $($Matches[1])"
            }
        }
    }

    $abiOut = Join-Path $OutputDir $target.Abi
    New-Item -ItemType Directory -Force -Path $abiOut | Out-Null
    Copy-Item -LiteralPath $library -Destination (Join-Path $abiOut 'libliquid_blur.so') -Force
    Write-Output "Rust native library ready: $($target.Abi) -> $abiOut"
}

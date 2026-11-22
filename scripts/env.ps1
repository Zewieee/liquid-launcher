# Shared environment loader for Liquid Launcher scripts.
# Loads optional repo-root `.env` without overwriting existing process env vars.

function Import-LiquidEnv {
    param([Parameter(Mandatory)][string]$RepoRoot)
    $envFile = Join-Path $RepoRoot '.env'
    if (Test-Path -LiteralPath $envFile) {
        foreach ($rawLine in (Get-Content -LiteralPath $envFile)) {
            $line = $rawLine.Trim()
            if (-not $line -or $line.StartsWith('#')) { continue }
            $idx = $line.IndexOf('=')
            if ($idx -le 0) { continue }
            $key = $line.Substring(0, $idx).Trim()
            $val = $line.Substring($idx + 1).Trim()
            if (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'"))) {
                if ($val.Length -ge 2) { $val = $val.Substring(1, $val.Length - 2) }
            }
            $existing = [Environment]::GetEnvironmentVariable($key, 'Process')
            if ([string]::IsNullOrWhiteSpace($existing) -and -not [string]::IsNullOrWhiteSpace($val)) {
                [Environment]::SetEnvironmentVariable($key, $val, 'Process')
            }
        }
    }
}

function Resolve-LiquidToolchain {
    param([Parameter(Mandatory)][string]$RepoRoot)
    Import-LiquidEnv -RepoRoot $RepoRoot

    $javaHome = $env:JAVA_HOME
    if ($javaHome -and (Test-Path -LiteralPath (Join-Path $javaHome 'bin\javac.exe'))) {
        $javac = Join-Path $javaHome 'bin\javac.exe'
        $jarCmd = Join-Path $javaHome 'bin\jar.exe'
        $java = Join-Path $javaHome 'bin\java.exe'
        $env:PATH = "$(Join-Path $javaHome 'bin');$env:PATH"
    } else {
        $javac = (Get-Command javac -ErrorAction Stop).Source
        $jarCmd = (Get-Command jar -ErrorAction Stop).Source
        $java = (Get-Command java -ErrorAction Stop).Source
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javac)
    }

    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk -and $env:LOCALAPPDATA) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not $sdk -or -not (Test-Path -LiteralPath $sdk)) {
        throw 'Android SDK not found. Set ANDROID_HOME in .env or environment.'
    }

    $btVer = if ($env:ANDROID_BUILD_TOOLS) { $env:ANDROID_BUILD_TOOLS } else { '35.0.0' }
    $platform = if ($env:ANDROID_PLATFORM) { $env:ANDROID_PLATFORM } else { 'android-35' }
    $btDir = Join-Path $sdk "build-tools\$btVer"
    $androidJar = Join-Path $sdk "platforms\$platform\android.jar"
    $adb = Join-Path $sdk 'platform-tools\adb.exe'

    if (-not (Test-Path -LiteralPath $androidJar)) {
        throw "Android platform jar not found: $androidJar"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $btDir 'd8.bat'))) {
        throw "Android build-tools $btVer not found under: $btDir"
    }

    return [pscustomobject]@{
        JavaHome   = $env:JAVA_HOME
        Java       = $java
        Javac      = $javac
        Jar        = $jarCmd
        Sdk        = $sdk
        BuildTools = $btDir
        AndroidJar = $androidJar
        Adb        = $adb
    }
}

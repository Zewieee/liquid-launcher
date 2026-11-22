$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$libsDir = Join-Path $root 'libs'
$checksumPath = Join-Path $libsDir 'checksums.json'
if (-not (Test-Path -LiteralPath $checksumPath)) {
    throw "Missing checksums file: $checksumPath"
}

$checksums = Get-Content -LiteralPath $checksumPath -Raw | ConvertFrom-Json
$urls = @{
    'kuromoji-core-0.9.0.jar'   = 'https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-core/0.9.0/kuromoji-core-0.9.0.jar'
    'kuromoji-ipadic-0.9.0.jar' = 'https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar'
    'snakeyaml-2.7.jar'         = 'https://repo1.maven.org/maven2/org/yaml/snakeyaml/2.7/snakeyaml-2.7.jar'
}

foreach ($name in $urls.Keys) {
    $expected = $checksums.$name
    if (-not $expected) { throw "No pinned SHA-256 found for $name" }
    $dest = Join-Path $libsDir $name
    if (-not (Test-Path -LiteralPath $dest)) {
        Write-Output "Downloading $name ..."
        Invoke-WebRequest -UseBasicParsing -Uri $urls[$name] -OutFile $dest
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $dest).Hash.ToLowerInvariant()
    if ($actual -ne $expected.ToLowerInvariant()) {
        Remove-Item -LiteralPath $dest -Force -ErrorAction SilentlyContinue
        throw "SHA-256 mismatch for ${name}: expected $expected, got $actual"
    }
    Write-Output "Verified $name ($actual)"
}
Write-Output 'ALL_PINNED_LIBS_READY'

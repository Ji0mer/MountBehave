param(
    [string]$Keystore,
    [string]$KeyAlias = "androiddebugkey",
    [string]$StorePass = "android",
    [string]$KeyPass = "android",
    [switch]$SkipSigning
)

. "$PSScriptRoot\env.ps1"

& "$ProjectRoot\gradlew.bat" -p "$ProjectRoot" :app:assembleRelease
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$ReleaseDir = Join-Path $ProjectRoot "app\build\outputs\apk\release"
$MetadataPath = Join-Path $ReleaseDir "output-metadata.json"
if (-not (Test-Path $MetadataPath)) {
    throw "Release output metadata not found: $MetadataPath"
}

$Metadata = Get-Content $MetadataPath -Raw | ConvertFrom-Json
$Element = $Metadata.elements | Select-Object -First 1
$UnsignedApk = Join-Path $ReleaseDir $Element.outputFile
if (-not (Test-Path $UnsignedApk)) {
    throw "Unsigned release APK not found: $UnsignedApk"
}

Write-Host "Unsigned release APK: $UnsignedApk"

if ($SkipSigning) {
    exit 0
}

if (-not $Keystore) {
    $DefaultDebugKeystore = Join-Path $env:USERPROFILE ".android\debug.keystore"
    if (-not (Test-Path $DefaultDebugKeystore)) {
        throw "No keystore supplied and Android debug keystore was not found. Re-run with -Keystore or -SkipSigning."
    }
    $Keystore = $DefaultDebugKeystore
    Write-Warning "No release keystore supplied; signing with the local Android debug keystore for install/testing."
}

$BuildTools = Join-Path $env:ANDROID_HOME "build-tools\36.0.0"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"
if (-not (Test-Path $ZipAlign)) {
    throw "zipalign not found: $ZipAlign"
}
if (-not (Test-Path $ApkSigner)) {
    throw "apksigner not found: $ApkSigner"
}

$DistDir = Join-Path $ProjectRoot "dist"
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

$SignedApk = Join-Path $DistDir ("MountBehave-v{0}.apk" -f $Element.versionName)
& $ZipAlign -p -f 4 $UnsignedApk $SignedApk
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $ApkSigner sign `
    --ks $Keystore `
    --ks-key-alias $KeyAlias `
    --ks-pass ("pass:{0}" -f $StorePass) `
    --key-pass ("pass:{0}" -f $KeyPass) `
    $SignedApk
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $ApkSigner verify --verbose $SignedApk
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Signed release APK: $SignedApk"

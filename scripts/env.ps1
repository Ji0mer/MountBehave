$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$JdkHome = Get-ChildItem (Join-Path $ProjectRoot '.toolchain\jdk') -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
    Select-Object -First 1 -ExpandProperty FullName
$SdkRoot = Join-Path $ProjectRoot '.toolchain\android-sdk'

if (-not $JdkHome) {
    throw "JDK not found under $ProjectRoot\.toolchain\jdk"
}
if (-not (Test-Path (Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'))) {
    throw "Android SDK command-line tools not found under $SdkRoot"
}

$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
$env:Path = "$JdkHome\bin;$SdkRoot\cmdline-tools\latest\bin;$SdkRoot\platform-tools;$env:Path"

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"

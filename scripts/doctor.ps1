. "$PSScriptRoot\env.ps1"

Write-Host ""
Write-Host "Java:"
& "$env:JAVA_HOME\bin\java.exe" -version

Write-Host ""
Write-Host "Android SDK packages:"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --sdk_root="$env:ANDROID_HOME" --list_installed

Write-Host ""
Write-Host "ADB:"
& "$env:ANDROID_HOME\platform-tools\adb.exe" version

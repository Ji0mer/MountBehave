$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\env.ps1"

$env:ANDROID_AVD_HOME = Join-Path $ProjectRoot '.avd'
$AvdName = 'OnStepPreview'
$ApkPath = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$PreviewPath = Join-Path $ProjectRoot 'preview-onstep-controller.png'
$AvdConfigPath = Join-Path $env:ANDROID_AVD_HOME "$AvdName.avd\config.ini"

function Get-PreviewEmulatorSerial {
    $lines = adb devices | Select-String '^\S+\s+device$'
    foreach ($line in $lines) {
        $serial = (($line.ToString() -split '\s+')[0])
        if ($serial -notmatch '^emulator-\d+$') {
            continue
        }
        try {
            $avdOutput = adb -s $serial emu avd name 2>$null
        } catch {
            continue
        }
        if ($LASTEXITCODE -ne 0) {
            continue
        }
        $avdName = $avdOutput |
                Where-Object { $_ -and $_.Trim() -and $_.Trim() -ne 'OK' } |
                Select-Object -First 1
        if ($avdName -and $avdName.Trim() -eq $AvdName) {
            return $serial
        }
    }
    return $null
}

function Wait-ForBoot($Serial) {
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        $booted = adb -s $Serial shell getprop sys.boot_completed 2>$null
        if ($booted -match '1') {
            return
        }
        Start-Sleep -Seconds 5
    }
    throw "Emulator $Serial did not finish booting in time."
}

function Wait-ForEmulatorExit($Serial) {
    $deadline = (Get-Date).AddMinutes(1)
    while ((Get-Date) -lt $deadline) {
        $line = adb devices | Select-String "$([regex]::Escape($Serial))\s+device"
        if (-not $line) {
            return
        }
        Start-Sleep -Seconds 2
    }
}

function Device-NeedsPreviewRestart($Serial) {
    $size = adb -s $Serial shell wm size 2>$null
    return ($size -notmatch '720x1280')
}

function Set-ConfigValue($Lines, $Name, $Value) {
    $pattern = "^$([regex]::Escape($Name))="
    $updated = $false
    $next = foreach ($line in $Lines) {
        if ($line -match $pattern) {
            $updated = $true
            "$Name=$Value"
        } else {
            $line
        }
    }
    if (-not $updated) {
        $next += "$Name=$Value"
    }
    return $next
}

function Ensure-PreviewAvdConfig {
    if (-not (Test-Path $AvdConfigPath)) {
        throw "AVD config was not found: $AvdConfigPath"
    }

    $lines = Get-Content -Path $AvdConfigPath -Encoding UTF8
    $lines = Set-ConfigValue $lines 'hw.lcd.width' '720'
    $lines = Set-ConfigValue $lines 'hw.lcd.height' '1280'
    $lines = Set-ConfigValue $lines 'hw.lcd.density' '320'
    $lines = Set-ConfigValue $lines 'showDeviceFrame' 'no'
    $lines = Set-ConfigValue $lines 'hw.keyboard' 'yes'
    Set-Content -Path $AvdConfigPath -Value $lines -Encoding UTF8
}

if (-not (Test-Path (Join-Path $env:ANDROID_AVD_HOME "$AvdName.avd"))) {
    throw "AVD '$AvdName' was not found. Create it with avdmanager before previewing."
}

Ensure-PreviewAvdConfig

$serial = Get-PreviewEmulatorSerial
if ($serial -and (Device-NeedsPreviewRestart $serial)) {
    Write-Host "Restarting $serial with compact preview display settings..."
    adb -s $serial emu kill | Out-Null
    Wait-ForEmulatorExit $serial
    $serial = $null
}

if (-not $serial) {
    $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
    Start-Process -FilePath $emulator -ArgumentList @(
        '-avd', $AvdName,
        '-no-boot-anim',
        '-no-snapshot-load',
        '-gpu', 'swiftshader_indirect',
        '-scale', '0.65'
    )

    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $deadline -and -not $serial) {
        Start-Sleep -Seconds 5
        $serial = Get-PreviewEmulatorSerial
    }
    if (-not $serial) {
        throw "Emulator did not appear in adb devices."
    }
}

Wait-ForBoot $serial

& "$ProjectRoot\scripts\build-debug.ps1"
adb -s $serial install -r $ApkPath
adb -s $serial shell am start -n com.example.onstepcontroller/.MainActivity
Start-Sleep -Seconds 2
adb -s $serial shell screencap -p /sdcard/onstep-preview.png
adb -s $serial pull /sdcard/onstep-preview.png $PreviewPath | Out-Host

Write-Host "Preview running on $serial"
Write-Host "Screenshot: $PreviewPath"

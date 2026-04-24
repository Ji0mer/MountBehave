# OnStep Controller

Android hand-controller project for an OnStep equatorial mount.

## Local Environment

This folder is configured with a portable toolchain under `.toolchain/`:

- JDK 17
- Android SDK command-line tools
- Android SDK platform 36
- Android SDK build-tools 36.0.0
- Android platform-tools
- Gradle wrapper, generated from Gradle 9.3.1

Use PowerShell from this folder:

```powershell
.\scripts\doctor.ps1
.\scripts\build-debug.ps1
```

The debug APK will be written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Install to a connected Android device:

```powershell
.\scripts\env.ps1
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Preview on this PC with the local Android emulator:

```powershell
.\scripts\preview-app.ps1
```

The preview script uses a compact `720x1280` emulator display at 65% scale so the whole emulator window fits on a normal desktop.

## Current State

The app now includes the first control slice:

- WiFi TCP connection to an OnStep command port
- `:GVP#` connection handshake
- Guide/Center/Find/Slew speed selection
- North/South/East/West movement commands
- Stop on button release, explicit stop, disconnect, and app backgrounding
- On-screen command log

To test without a mount, run:

```powershell
.\scripts\mock-onstep.ps1
```

Then connect from the Android emulator to `10.0.2.2:9999`, or from a real phone to this PC's LAN IP on port `9999`.

The implementation plan is in `plan.md`.

Sky chart data details are in `docs\catalog-data.md`.

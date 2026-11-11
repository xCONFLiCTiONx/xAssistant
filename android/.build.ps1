#Requires -Version 5.1
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

# Use the Android Studio JBR already installed on this computer.
# This script never downloads or installs a JDK.
$AndroidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
$JavaExe = Join-Path $AndroidStudioJbr "bin\java.exe"
if (-not (Test-Path $JavaExe)) { throw "Android Studio JBR not found at $JavaExe. No JDK will be downloaded." }
$env:JAVA_HOME = $AndroidStudioJbr
$env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
$versionError = Join-Path $env:TEMP ("xassistant-java-version-" + [guid]::NewGuid().ToString("N") + ".txt")
try {
    $versionProcess = Start-Process -FilePath $JavaExe -ArgumentList "-version" -NoNewWindow -Wait -PassThru -RedirectStandardError $versionError
    $JavaVersionOutput = Get-Content -LiteralPath $versionError -Raw -ErrorAction SilentlyContinue
    if ($versionProcess.ExitCode -ne 0) { throw "Java version check failed: $JavaVersionOutput" }
} finally {
    Remove-Item -LiteralPath $versionError -Force -ErrorAction SilentlyContinue
}
if ($JavaVersionOutput -notmatch 'version "25\.') { throw "Expected installed Android Studio JBR 25.x. Found: $JavaVersionOutput" }
Write-Host "==> Using Android Studio JBR: $env:JAVA_HOME" -ForegroundColor Cyan

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $sdkCandidates = @((Join-Path $env:LOCALAPPDATA "Android\Sdk"), "C:\Android\sdk")
    foreach ($candidate in $sdkCandidates) {
        if (Test-Path $candidate) { $env:ANDROID_HOME = $candidate; break }
    }
}
if (-not $env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
if ($env:ANDROID_HOME) { Write-Host "==> Using ANDROID_HOME: $env:ANDROID_HOME" -ForegroundColor Cyan }

# Locate adb.exe.
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) {
    $AdbPath = $adbCmd.Source
} else {
    $Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
    if (-not $Sdk) { throw "ANDROID_HOME/ANDROID_SDK_ROOT is not set and adb.exe was not found in PATH." }
    $sdkAdb = Join-Path $Sdk "platform-tools\adb.exe"
    if (Test-Path $sdkAdb) { $AdbPath = $sdkAdb } else { throw "Could not find adb.exe in PATH or $sdkAdb." }
}

# Require exactly one authorized device, then identify its form factor.
Write-Host "==> Detecting connected Android device..." -ForegroundColor Cyan
$devicesOutput = & $AdbPath devices
if ($LASTEXITCODE -ne 0) { throw "Could not query Android devices through ADB." }
$deviceLines = @($devicesOutput | Select-Object -Skip 1 | Where-Object { "$_".Trim() })
$unauthorizedDevices = @($deviceLines | Where-Object { $_ -match "^\S+\s+unauthorized\s*$" })
$connectedDevices = @($deviceLines | Where-Object { $_ -match "^\S+\s+device\s*$" })

if ($connectedDevices.Count -eq 0) {
    Write-Host ($devicesOutput | Out-String) -ForegroundColor Yellow
    if ($unauthorizedDevices.Count -gt 0) { throw "The connected device is unauthorized. Unlock it, approve USB debugging, and rerun." }
    throw "No authorized Android device detected."
}
if ($connectedDevices.Count -gt 1) {
    Write-Host ($connectedDevices | Out-String) -ForegroundColor Yellow
    throw "More than one Android device is connected. Leave only the target device connected and rerun."
}

$Serial = ($connectedDevices[0] -split "\s+")[0]
$Characteristics = (& $AdbPath -s $Serial shell getprop ro.build.characteristics 2>$null | Out-String).Trim()
$Model = (& $AdbPath -s $Serial shell getprop ro.product.model 2>$null | Out-String).Trim()
$Device = (& $AdbPath -s $Serial shell getprop ro.product.device 2>$null | Out-String).Trim()

if ($LASTEXITCODE -ne 0) { throw "Could not identify the connected device through ADB." }

if ($Characteristics -match "(^|,)watch(,|$)" -or $Model -match "^SM-R\d+" -or $Device -match "watch") {
    $Target = "Wear OS watch"
    $Module = "wear"
    $GradleTask = ":wear:assembleDebug"
    $ApkPath = Join-Path $PSScriptRoot "wear\build\outputs\apk\debug\wear-debug.apk"
    $GradleFile = Join-Path $PSScriptRoot "wear\build.gradle.kts"
} else {
    $Target = "Android phone"
    $Module = "app"
    $GradleTask = ":app:assembleDebug"
    $ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
    $GradleFile = Join-Path $PSScriptRoot "app\build.gradle.kts"
}

Write-Host "==> Device: $Model ($Device)" -ForegroundColor Green
Write-Host "==> Detected target: $Target" -ForegroundColor Green
Write-Host "==> ADB serial: $Serial" -ForegroundColor Green

# Use the exact Gradle distribution already cached by the user.
# This script never downloads or installs Gradle.
$GradleExecutable = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0\bin\gradle.bat"
if (-not (Test-Path $GradleExecutable)) { throw "Cached Gradle 9.6.0 was not found at $GradleExecutable. No Gradle distribution will be downloaded." }
$GradleVersionOutput = (& $GradleExecutable --version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $GradleVersionOutput -notmatch "Gradle 9\.6\.0") { throw "Expected cached Gradle 9.6.0. Version check failed: $GradleVersionOutput" }
Write-Host "==> Using cached Gradle 9.6.0" -ForegroundColor Cyan

Write-Host "==> Building $Target APK..." -ForegroundColor Cyan
& $GradleExecutable --no-daemon $GradleTask
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
if (-not (Test-Path $ApkPath)) { throw "Built APK not found at: $ApkPath" }

$apkSizeMb = [Math]::Round((Get-Item $ApkPath).Length / 1MB, 2)
Write-Host "==> APK built successfully ($apkSizeMb MB): $ApkPath" -ForegroundColor Green

# Resolve the application ID for the selected device target.
$GradleContent = Get-Content $GradleFile -Raw
$PackageMatch = [regex]::Match($GradleContent, '(?m)applicationId\s*=\s*"([^"]+)"')
if (-not $PackageMatch.Success) { throw "Could not determine applicationId from $GradleFile" }
$PackageName = $PackageMatch.Groups[1].Value
Write-Host "==> Package: $PackageName" -ForegroundColor Green

# Install as an update. Never uninstall or clear existing app data.
Write-Host "==> Installing $Target APK..." -ForegroundColor Cyan
$installOutput = & $AdbPath -s $Serial install -r $ApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    $installText = ($installOutput | Out-String)
    Write-Host $installText -ForegroundColor Red
    if ($installText -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        throw "The installed app uses a different signing key. Nothing was uninstalled and app data was not erased."
    }
    throw "adb install failed with exit code $LASTEXITCODE"
}
Write-Host ($installOutput | Out-String).Trim() -ForegroundColor Gray

# Launch the installed app.
Write-Host "==> Resolving launcher activity..." -ForegroundColor Cyan
$ActivityOutput = & $AdbPath -s $Serial shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PackageName 2>$null
if ($LASTEXITCODE -ne 0) { throw "Could not resolve launcher activity through ADB." }
$Activity = $ActivityOutput |
    ForEach-Object { "$_".Trim() } |
    Where-Object { $_ -match '^[^/]+/.+$' } |
    Select-Object -Last 1
if (-not $Activity) { throw "No launcher activity found for $PackageName. The APK was installed, but could not be launched." }

Write-Host "==> Launching $Activity..." -ForegroundColor Cyan
& $AdbPath -s $Serial shell am start -n $Activity
if ($LASTEXITCODE -ne 0) { throw "Failed to launch the app through ADB." }
Write-Host "==> Done! xAssistant for $Target is installed and running." -ForegroundColor Green

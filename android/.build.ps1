#Requires -Version 5.1
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

$BuildVariant = "Debug"
$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"

# Use the Android Studio JBR already installed on this computer.
# This script never downloads or installs a JDK.
$AndroidStudioJbr = "C:\Program Files\Android\Android Studio\jbr"
$JavaExe = Join-Path $AndroidStudioJbr "bin\java.exe"
if (-not (Test-Path $JavaExe)) { throw "Android Studio JBR not found at $JavaExe. No JDK will be downloaded." }
$env:JAVA_HOME = $AndroidStudioJbr
$env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
$JavaVersionOutput = (& $JavaExe -version 2>&1 | Out-String)
if ($JavaVersionOutput -notmatch 'version "25\.') { throw "Expected installed Android Studio JBR 25.x. Found: $JavaVersionOutput" }
Write-Host "==> Using Android Studio JBR: $env:JAVA_HOME" -ForegroundColor Cyan
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $sdkCandidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk"),
        "C:\Android\sdk"
    )
    foreach ($candidate in $sdkCandidates) {
        if (Test-Path $candidate) {
            $env:ANDROID_HOME = $candidate
            break
        }
    }
}
if (-not $env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) {
    $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
}
if (-not $env:ANDROID_SDK_ROOT -and $env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}

if ($env:ANDROID_HOME) {
    Write-Host "==> Using ANDROID_HOME: $env:ANDROID_HOME" -ForegroundColor Cyan
}

# Use the exact Gradle distribution already cached by the user.
# This script never downloads or installs Gradle.
$GradleExecutable = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat"
if (-not (Test-Path $GradleExecutable)) { throw "Cached Gradle 9.5.0 was not found at $GradleExecutable. No Gradle distribution will be downloaded." }
$GradleVersionOutput = (& $GradleExecutable --version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $GradleVersionOutput -notmatch "Gradle 9\.5\.0") { throw "Expected cached Gradle 9.5.0. Version check failed: $GradleVersionOutput" }
Write-Host "==> Using cached Gradle 9.5.0: $GradleExecutable" -ForegroundColor Cyan
Write-Host "==> Building Debug APK..." -ForegroundColor Cyan
& $GradleExecutable --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) {
    throw "Gradle $BuildVariant build failed with exit code $LASTEXITCODE"
}
if (-not (Test-Path $ApkPath)) {
    throw "Built $BuildVariant APK not found at: $ApkPath"
}

$apkSizeMb = [Math]::Round((Get-Item $ApkPath).Length / 1MB, 2)
Write-Host "==> $BuildVariant APK built successfully ($apkSizeMb MB): $ApkPath" -ForegroundColor Green

# 3. Locate adb.exe.
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCmd) {
    $AdbPath = $adbCmd.Source
} else {
    $Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
    if (-not $Sdk) {
        throw "ANDROID_HOME/ANDROID_SDK_ROOT is not set and adb.exe was not found in PATH."
    }
    $sdkAdb = Join-Path $Sdk "platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        $AdbPath = $sdkAdb
    } else {
        throw "Could not find adb.exe in PATH or $sdkAdb. Install Android SDK Platform-Tools."
    }
}

# 4. Verify that exactly one authorized device is connected.
Write-Host "==> Checking connected Android device(s)..." -ForegroundColor Cyan
$devicesOutput = & $AdbPath devices
if ($LASTEXITCODE -ne 0) {
    throw "Could not query Android devices through ADB."
}
$deviceLines = @($devicesOutput | Select-Object -Skip 1 | Where-Object { "$_".Trim() })
$unauthorizedDevices = @($deviceLines | Where-Object { $_ -match "^\S+\s+unauthorized\s*$" })
$connectedDevices = @($deviceLines | Where-Object { $_ -match "^\S+\s+device\s*$" })

if ($connectedDevices.Count -eq 0) {
    Write-Host ($devicesOutput | Out-String) -ForegroundColor Yellow
    if ($unauthorizedDevices.Count -gt 0) {
        throw "The phone is connected but unauthorized. Unlock it, approve the USB debugging prompt, and rerun this script."
    }
    throw "No authorized Android device detected. Connect the phone and enable USB or Wireless debugging."
}
if ($connectedDevices.Count -gt 1) {
    Write-Host ($connectedDevices | Out-String) -ForegroundColor Yellow
    throw "More than one Android device is connected. Disconnect the extra device and rerun this script."
}
$Serial = ($connectedDevices[0] -split "\s+")[0]
Write-Host "==> Target device: $Serial" -ForegroundColor Green

# 5. Resolve the application ID from the Android Gradle configuration.
Write-Host "==> Resolving package name..." -ForegroundColor Cyan
$GradleFile = Join-Path $PSScriptRoot "app\build.gradle.kts"
$GradleContent = Get-Content $GradleFile -Raw
$PackageMatch = [regex]::Match($GradleContent, '(?m)^\s*applicationId\s*=\s*"([^"]+)"')
if (-not $PackageMatch.Success) {
    throw "Could not determine applicationId from $GradleFile"
}
$PackageName = $PackageMatch.Groups[1].Value
Write-Host "==> Package Name: $PackageName" -ForegroundColor Green

# 6. Install as an update. Never uninstall or clear existing app data.
Write-Host "==> Installing $BuildVariant APK..." -ForegroundColor Cyan
$installOutput = & $AdbPath -s $Serial install -r $ApkPath 2>&1
if ($LASTEXITCODE -ne 0) {
    $installText = ($installOutput | Out-String)
    Write-Host $installText -ForegroundColor Red
    if ($installText -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
        throw "The installed app uses a different signing key. The script will not uninstall it or erase app data. Use the original signing key."
    }
    throw "adb install failed with exit code $LASTEXITCODE"
}
Write-Host ($installOutput | Out-String).Trim() -ForegroundColor Gray

# 7. Resolve and launch the installed app's launcher activity.
Write-Host "==> Resolving launcher activity..." -ForegroundColor Cyan
$ActivityOutput = & $AdbPath -s $Serial shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PackageName 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Could not resolve launcher activity through ADB."
}
$Activity = $ActivityOutput |
    ForEach-Object { "$_".Trim() } |
    Where-Object { $_ -match '^[^/]+/.+$' } |
    Select-Object -Last 1
if (-not $Activity) {
    throw "No launcher activity found for $PackageName. The APK was installed, but could not be launched."
}

Write-Host "==> Launching $Activity..." -ForegroundColor Cyan
& $AdbPath -s $Serial shell am start -n $Activity
if ($LASTEXITCODE -ne 0) {
    throw "Failed to launch the app through ADB."
}
Write-Host "==> Done! xAssistant ($BuildVariant) is installed and running." -ForegroundColor Green

#Requires -Version 5.1
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

$BuildVariant = "Debug"
$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"

# 1. Configure JAVA_HOME and ANDROID_HOME when they are not already configured.
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jbrCandidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )
    foreach ($candidate in $jbrCandidates) {
        if (Test-Path (Join-Path $candidate "bin\java.exe")) {
            $env:JAVA_HOME = $candidate
            break
        }
    }
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JDK 17 was not found. Install Android Studio or set JAVA_HOME to a JDK 17 installation."
}

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

Write-Host "==> Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
if ($env:ANDROID_HOME) {
    Write-Host "==> Using ANDROID_HOME: $env:ANDROID_HOME" -ForegroundColor Cyan
}

# 2. Find Gradle. Prefer the repository wrapper, then PATH, then cached distributions.
$GradleBat = Join-Path $PSScriptRoot "gradlew.bat"
$GradleCommand = Get-Command gradle -ErrorAction SilentlyContinue
$CachedGradle = $null

if (-not (Test-Path $GradleBat) -and -not $GradleCommand) {
    $GradleCache = Join-Path $env:USERPROFILE ".gradlewrapperdists"
    if (Test-Path $GradleCache) {
        $CachedGradle = Get-ChildItem $GradleCache -Filter "gradle.bat" -File -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "gradle-8\.(11|1[2-9]|[2-9]\d)\." } |
            Sort-Object FullName -Descending |
            Select-Object -First 1
    }
}

if (Test-Path $GradleBat) {
    Write-Host "==> Building Debug APK with repository Gradle wrapper..." -ForegroundColor Cyan
    & $GradleBat --no-daemon :app:assembleDebug
} elseif ($GradleCommand) {
    Write-Host "==> Building Debug APK with installed Gradle..." -ForegroundColor Cyan
    & $GradleCommand.Source --no-daemon :app:assembleDebug
} elseif ($CachedGradle) {
    Write-Host "==> Building Debug APK with cached Gradle..." -ForegroundColor Cyan
    & $CachedGradle.FullName --no-daemon :app:assembleDebug
} else {
    throw "Gradle 8.11.1 or newer was not found. Install it or open the project in Android Studio once so Gradle can be downloaded, then rerun this script."
}
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

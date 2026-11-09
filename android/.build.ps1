#Requires -Version 5.1
$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

$BuildVariant = "Debug"
$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"

# 1. Use only Java installations already present on this computer.
# Do not download or install a JDK automatically.
$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$jdkCandidates += @(
    "C:\Program Files\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
)

$CompatibleJdk = $null
$DetectedJava = @()
foreach ($candidate in ($jdkCandidates | Select-Object -Unique)) {
    $javaExe = Join-Path $candidate "bin\java.exe"
    if (-not (Test-Path $javaExe)) { continue }

    $versionError = Join-Path $env:TEMP ("xassistant-java-version-" + [guid]::NewGuid().ToString("N") + ".txt")
    try {
        $versionProcess = Start-Process -FilePath $javaExe -ArgumentList "-version" -NoNewWindow -Wait -PassThru -RedirectStandardError $versionError
        $versionOutput = Get-Content -LiteralPath $versionError -Raw -ErrorAction SilentlyContinue
    } finally {
        Remove-Item -LiteralPath $versionError -Force -ErrorAction SilentlyContinue
    }
    if ($versionProcess.ExitCode -ne 0) { continue }

    if ($versionOutput -match 'version "?([0-9]+)') {
        $javaMajor = [int]$Matches[1]
        $DetectedJava += "Java $javaMajor ($candidate)"
        if (-not $CompatibleJdk) { $CompatibleJdk = $candidate }
    }
}

if (-not $CompatibleJdk) {
    throw "No installed JDK was found. Install/configure your preferred JDK and set JAVA_HOME. This script will not download one."
}

$env:JAVA_HOME = $CompatibleJdk
$env:PATH = "$(Join-Path $env:JAVA_HOME 'bin');$env:PATH"
Write-Host "==> Using installed JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Cyan
Write-Host "==> Detected: $($DetectedJava -join '; ')" -ForegroundColor Gray

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

# 2. Use only Gradle already installed or already cached by the user.
# Never download a Gradle distribution automatically.
$GradleCommand = Get-Command gradle -ErrorAction SilentlyContinue
$GradleExecutable = $null
if ($GradleCommand) {
    $GradleExecutable = $GradleCommand.Source
} else {
    $GradleCache = Join-Path $env:USERPROFILE ".gradle\wrapper\dists"
    if (Test-Path $GradleCache) {
        $CachedGradle = Get-ChildItem $GradleCache -Filter "gradle.bat" -File -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($CachedGradle) { $GradleExecutable = $CachedGradle.FullName }
    }
}
if (-not $GradleExecutable) {
    throw "No installed/cached Gradle was found. This script will not download one. Run 'gradle --version' or tell me which Gradle versions are installed."
}

Write-Host "==> Using Gradle: $GradleExecutable" -ForegroundColor Cyan
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

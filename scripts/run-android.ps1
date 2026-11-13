param(
    [string]$DeviceSerial,
    [string]$DeviceHardwareSerial,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$deviceSelection = if ($DeviceSerial -or $DeviceHardwareSerial) { 'command line' } else { 'automatic' }
$sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
$localProperties = Join-Path $projectRoot 'local.properties'
if (Test-Path -LiteralPath $localProperties) {
    $propertyLines = Get-Content -LiteralPath $localProperties
    $sdkLine = $propertyLines | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if ($sdkLine) {
        $sdkRoots += ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Replace('\:', ':').Replace('\\', '\')
    }
    if (-not $DeviceSerial -and -not $DeviceHardwareSerial) {
        $hardwareLine = $propertyLines | Where-Object { $_ -match '^\s*adb\.device\.hardware\.serial\s*=' } | Select-Object -First 1
        if ($hardwareLine) {
            $DeviceHardwareSerial = ($hardwareLine -split '=', 2)[1].Trim()
            if ($DeviceHardwareSerial) { $deviceSelection = 'local.properties hardware serial' }
        }
    }
    if (-not $DeviceSerial -and -not $DeviceHardwareSerial) {
        $serialLine = $propertyLines | Where-Object { $_ -match '^\s*adb\.device\.serial\s*=' } | Select-Object -First 1
        if ($serialLine) {
            $DeviceSerial = ($serialLine -split '=', 2)[1].Trim().Replace('\:', ':').Replace('\\', '\')
            if ($DeviceSerial) { $deviceSelection = 'local.properties' }
        }
    }
}
$sdkRoots += Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = $sdkRoots | Where-Object { $_ } | ForEach-Object { Join-Path $_ 'platform-tools\adb.exe' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $adb) { throw 'ADB not found. Set ANDROID_HOME or sdk.dir in local.properties.' }

$deviceLines = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'ADB could not list devices.' }
$onlineDevices = @($deviceLines | ForEach-Object {
    if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
if ($DeviceSerial) {
    if ($DeviceSerial -notin $onlineDevices) { throw "Device is not online: $DeviceSerial" }
} elseif ($DeviceHardwareSerial) {
    $matchingTransports = @()
    foreach ($transport in ($onlineDevices | Where-Object { $_ -notmatch '^emulator-' })) {
        $hardwareOutput = & $adb -s $transport shell getprop ro.serialno 2>$null
        if ($LASTEXITCODE -ne 0) { continue }
        $hardwareSerial = ($hardwareOutput -join '').Trim()
        if (-not $hardwareSerial) {
            $hardwareOutput = & $adb -s $transport shell getprop ro.boot.serialno 2>$null
            if ($LASTEXITCODE -ne 0) { continue }
            $hardwareSerial = ($hardwareOutput -join '').Trim()
        }
        if ($hardwareSerial -ceq $DeviceHardwareSerial) { $matchingTransports += $transport }
    }
    if ($matchingTransports.Count -eq 0) {
        throw "Phone with hardware serial $DeviceHardwareSerial is not connected or its serial cannot be read. Enable wireless debugging and connect it using ADB."
    }
    # The same phone may have USB and Wi-Fi transports. Prefer Wi-Fi.
    $wifiTransports = @($matchingTransports | Where-Object { $_ -match '\._adb-tls-connect\._tcp\.?$|:\d+$' })
    $DeviceSerial = if ($wifiTransports.Count -gt 0) { $wifiTransports[0] } else { $matchingTransports[0] }
    Write-Host "Phone hardware serial: $DeviceHardwareSerial"
} else {
    $phones = @($onlineDevices | Where-Object { $_ -notmatch '^emulator-' })
    if ($phones.Count -eq 0) { throw 'No phone connected. Enable wireless debugging and pair/connect using adb.' }
    if ($phones.Count -gt 1) { throw "Multiple phones connected. Set adb.device.hardware.serial in local.properties or pass -DeviceSerial. Devices: $($phones -join ', ')" }
    $DeviceSerial = $phones[0]
}
Write-Host "Android device: $DeviceSerial ($deviceSelection)"
if ($CheckOnly) { return }

Push-Location $projectRoot
try {
    & .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Debug build failed.' }

    $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    & $adb -s $DeviceSerial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK installation failed. If signatures differ, use a separate QA installation; do not uninstall an app containing your data.' }

    $idLine = Get-Content -LiteralPath 'app-info.properties' | Where-Object { $_ -match '^application\.id=' } | Select-Object -First 1
    if (-not $idLine) { throw 'Missing application.id in app-info.properties.' }
    $applicationId = ($idLine -split '=', 2)[1].Trim()
    $launchOutput = & $adb -s $DeviceSerial shell am start -W -n "$applicationId/app.keyra.MainActivity" 2>&1
    $launchExitCode = $LASTEXITCODE
    $launchOutput | ForEach-Object { Write-Host $_ }
    if ($launchExitCode -ne 0 -or ($launchOutput -match '^Error:')) { throw 'Android could not launch the app.' }
} finally {
    Pop-Location
}

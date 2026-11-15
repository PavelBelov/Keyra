<#
Runs synthetic Android tests in the separate .qa application package.
Example: ./scripts/test-qa.ps1 -TestClass app.keyra.SyncMergeTest
Framework Autofill test getters need --no-hidden-api-checks for this instrumentation process.
No device-wide hidden API policy or production application data is changed.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$TestClass,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $projectRoot
try {
    if ($env:OS -eq 'Windows_NT') {
        # Force the JDK Pipe implementation to fall back to TCP on Windows hosts where
        # AF_UNIX bind succeeds but connect fails. This directory is intentionally absent.
        $socketPath = Join-Path $projectRoot ".gradle/unused-socket-$([guid]::NewGuid())"
        $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djdk.net.unixdomain.tmpdir=`"$socketPath`"".Trim()
    }
    if (!$SkipBuild) {
        & ./gradlew.bat :app:assembleQa :app:assembleQaAndroidTest -PandroidTestBuildType=qa --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'QA build failed.' }
    }
    $appDirectory = Join-Path $projectRoot 'app/build/outputs/apk/qa'
    $testDirectory = Join-Path $projectRoot 'app/build/outputs/apk/androidTest/qa'
    $appMetadata = Get-Content -LiteralPath (Join-Path $appDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    $testMetadata = Get-Content -LiteralPath (Join-Path $testDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
    if (!$appMetadata.applicationId.EndsWith('.qa') -or $testMetadata.applicationId -ne "$($appMetadata.applicationId).test") {
        throw 'Refusing to install APKs outside the isolated QA package.'
    }
    $adbCommand = (Get-Command adb -ErrorAction Stop).Source
    $deviceArguments = @()
    if ($Serial) { $deviceArguments = @('-s', $Serial) }
    foreach ($apk in @(
        (Join-Path $appDirectory $appMetadata.elements[0].outputFile),
        (Join-Path $testDirectory $testMetadata.elements[0].outputFile)
    )) {
        & $adbCommand @deviceArguments install -r $apk
        if ($LASTEXITCODE -ne 0) { throw "Could not install $apk" }
    }
    & $adbCommand @deviceArguments shell input keyevent KEYCODE_WAKEUP
    if ($LASTEXITCODE -ne 0) { throw 'Could not wake the test device.' }
    # Dismisses an already-authorized keyguard; it cannot bypass a PIN/password.
    & $adbCommand @deviceArguments shell wm dismiss-keyguard
    if ($LASTEXITCODE -ne 0) { throw 'Unlock the test device before running UI tests.' }
    $runnerArguments = @('shell', 'am', 'instrument', '--no-hidden-api-checks', '-w', '-r')
    if ($TestClass) { $runnerArguments += @('-e', 'class', $TestClass) }
    $runnerArguments += "$($testMetadata.applicationId)/androidx.test.runner.AndroidJUnitRunner"
    $reportDirectory = Join-Path $projectRoot 'app/build/reports'
    New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
    $report = Join-Path $reportDirectory 'qa-instrumentation.log'
    & $adbCommand @deviceArguments @runnerArguments 2>&1 | Tee-Object -FilePath $report
    $exitCode = $LASTEXITCODE
    # am instrument can return exit code zero even when JUnit assertions failed.
    if ($exitCode -ne 0 -or (Get-Content -LiteralPath $report -Raw) -notmatch 'OK \(\d+ tests?\)') {
        throw "Android tests failed. Report: $report"
    }
    Write-Host "Android tests passed. Report: $report"
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}

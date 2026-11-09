param([string[]]$Tasks = @(':core:test', ':app:assembleDebug', ':app:lintDebug'))
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
$taskJdk = Join-Path $taskRoot '.tools/java/jdk-17.0.21+9'
if (Test-Path -LiteralPath $taskJdk) { $env:JAVA_HOME = $taskJdk }
if (Test-Path -LiteralPath (Join-Path $taskRoot '.tools/android-sdk')) { $env:ANDROID_HOME = Join-Path $taskRoot '.tools/android-sdk' }
$env:GRADLE_USER_HOME = Join-Path $taskRoot '.tools/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $taskRoot '.tools/android-user'
if (Test-Path -LiteralPath (Join-Path $taskRoot '.tools/gradle-8.11.1/bin/gradle.bat')) {
    & (Join-Path $taskRoot '.tools/gradle-8.11.1/bin/gradle.bat') @Tasks --console=plain
} else { & (Join-Path $taskRoot 'gradlew.bat') @Tasks --console=plain }
exit $LASTEXITCODE

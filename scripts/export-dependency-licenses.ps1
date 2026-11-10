param([string]$GradleCachePath)
$ErrorActionPreference = 'Stop'
$auditRoot = Split-Path -Parent $PSScriptRoot
if (-not $GradleCachePath) { $GradleCachePath = Join-Path $auditRoot '.tools/gradle-home/caches/modules-2/files-2.1' }

function Get-Pom([string]$Coordinate) {
    $parts = $Coordinate.Split(':')
    $directory = Join-Path $GradleCachePath ($parts -join '/')
    $file = Get-ChildItem -LiteralPath $directory -Recurse -Filter '*.pom' | Select-Object -First 1
    if (-not $file) { throw "Missing cached POM for $Coordinate" }
    [xml]$xml = [IO.File]::ReadAllText($file.FullName)
    [pscustomobject]@{ Coordinate=$Coordinate; File=$file.FullName; Xml=$xml }
}

function Get-PomUrl([string]$Coordinate) {
    $group, $artifact, $version = $Coordinate.Split(':')
    $repository = if ($group.StartsWith('androidx.')) { 'https://dl.google.com/dl/android/maven2' } else { 'https://repo.maven.apache.org/maven2' }
    "$repository/$($group.Replace('.', '/'))/$artifact/$version/$artifact-$version.pom"
}

function Get-LicensePom($Pom, [string[]]$Seen = @()) {
    if ($Seen -contains $Pom.Coordinate) { throw 'POM parent cycle' }
    $licenses = @($Pom.Xml.project.licenses.license | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.name) })
    if ($licenses.Count -gt 0) { return $Pom }
    $parent = $Pom.Xml.project.parent
    if (-not $parent) { throw "No declared license for $($Pom.Coordinate)" }
    $parentCoordinate = "$($parent.groupId):$($parent.artifactId):$($parent.version)"
    Get-LicensePom (Get-Pom $parentCoordinate) ($Seen + $Pom.Coordinate)
}

$inputPath = Join-Path $auditRoot 'dist/fdroid-release-artifacts.json'
$artifacts = Get-Content -LiteralPath $inputPath -Raw | ConvertFrom-Json
$rows = foreach ($artifact in $artifacts) {
    $pom = Get-Pom $artifact.coordinate
    $licensePom = Get-LicensePom $pom
    $licenses = @($licensePom.Xml.project.licenses.license)
    $spdx = @($licenses | ForEach-Object {
        switch -Regex ([string]$_.name) {
            'Apache.*2\.0' { 'Apache-2.0'; break }
            '^MIT$' { 'MIT'; break }
            default { throw "Review license '$($_.name)' for $($artifact.coordinate) before extending this mapping" }
        }
    } | Sort-Object -Unique)
    [ordered]@{
        coordinate = $artifact.coordinate
        file = $artifact.file
        type = $artifact.type
        sha256 = $artifact.sha256
        licenses = $spdx
        declaredLicenseNames = @($licenses | ForEach-Object { [string]$_.name })
        pomUrl = Get-PomUrl $artifact.coordinate
        pomSha256 = (Get-FileHash -LiteralPath $pom.File -Algorithm SHA256).Hash.ToLowerInvariant()
        licensePomCoordinate = $licensePom.Coordinate
        licensePomUrl = Get-PomUrl $licensePom.Coordinate
        licensePomSha256 = (Get-FileHash -LiteralPath $licensePom.File -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$report = [ordered]@{
    schemaVersion = 1
    applicationId = ([regex]::Match([IO.File]::ReadAllText((Join-Path $auditRoot 'app-info.properties')), '(?m)^application\.id=(.+)$')).Groups[1].Value.Trim()
    scope = 'Selected external releaseRuntimeClasspath artifacts before R8 shrinking; excludes build/test tooling and vendored source/artwork'
    artifactCount = @($rows).Count
    artifacts = @($rows)
}
$outputPath = Join-Path $auditRoot 'fdroid/dependency-audit.json'
$reportText = ($report | ConvertTo-Json -Depth 10) + "`n"
[IO.File]::WriteAllText($outputPath, $reportText, [Text.UTF8Encoding]::new($false))
Write-Output "Exported $(@($rows).Count) reviewed runtime artifacts to fdroid/dependency-audit.json"

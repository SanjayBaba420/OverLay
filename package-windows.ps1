param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist\native')
)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome) {
    throw 'Supply -JdkHome with the path to a JDK containing jpackage.exe.'
}
$jpackage = Join-Path $JdkHome 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) { throw "jpackage not found: $jpackage" }
if (Test-Path (Join-Path $OutputDirectory 'OverlayNotes')) {
throw 'Output already contains OverlayNotes. Choose another -OutputDirectory to avoid overwriting notes.'
}
Push-Location $PSScriptRoot
try {
    & mvn -q package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
    $inputDirectory = Join-Path $PSScriptRoot 'target\windows-package-input'
    if (Test-Path $inputDirectory) { Remove-Item $inputDirectory -Recurse -Force }
    New-Item -ItemType Directory -Path $inputDirectory | Out-Null
    Copy-Item 'target\OverlayNotes-1.0-SNAPSHOT.jar' $inputDirectory
    Copy-Item 'target\dependency\*.jar' $inputDirectory
    & $jpackage --type app-image --name OverlayNotes --app-version 1.0.0 `
        --input $inputDirectory --main-jar OverlayNotes-1.0-SNAPSHOT.jar `
        --main-class org.OverlayNotes.Main --dest $OutputDirectory `
        --java-options '--enable-native-access=ALL-UNNAMED' `
        --add-modules java.desktop,java.logging,jdk.unsupported
    if ($LASTEXITCODE -ne 0) { throw 'Windows packaging failed.' }
    Write-Output "Ready: $OutputDirectory\OverlayNotes\OverlayNotes.exe"
} finally {
    Pop-Location
}

# Builds the HL7 Sender installer for Windows (on Linux or macOS use package.sh).
#
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1           MSI installer and portable .zip
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1 -Image    only the ready-to-run application folder
#
# Output: app\build\jpackage\installer (installers) and app\build\jpackage\image (application folder).
param([switch]$Image)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$out = Join-Path $root 'app\build\jpackage\installer'
$version = ((Get-Content gradle.properties | Where-Object { $_ -like 'version=*' }) -split '=', 2)[1]

function Say($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "Error: $text" -ForegroundColor Red; exit 1 }
function Gradle {
    & .\gradlew.bat @args
    if ($LASTEXITCODE -ne 0) { Fail "Gradle failed (exit code $LASTEXITCODE)." }
}

# JDK 21 is required: Gradle runs on it, and jlink/jpackage come from it.
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Fail 'Java not found. Install JDK 21 (for example Temurin 21) and try again.'
}
$javaVersion = (cmd /c 'java -XshowSettings:properties -version 2>&1') |
    Select-String 'java.specification.version = (\S+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
if ($javaVersion -ne '21') { Write-Warning "'java' is version $javaVersion; JDK 21 is expected." }

Say "Building the application folder (version $version)"
Gradle :app:jpackageImage
if ($Image) {
    Say 'Done: app\build\jpackage\image'
    exit 0
}
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

# jpackage builds the MSI with WiX 3 (candle.exe and light.exe).
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    $wix = Get-ChildItem 'C:\Program Files (x86)\WiX Toolset v3*\bin' -ErrorAction SilentlyContinue |
        Select-Object -Last 1
    if ($wix) {
        $env:PATH = "$($wix.FullName);$env:PATH"
    } else {
        Fail ('WiX Toolset 3 is needed for the MSI. Install it with: choco install wixtoolset --version 3.14.1 ' +
            '(or from https://github.com/wixtoolset/wix3/releases), or run with -Image for the folder only.')
    }
}

Say 'Building the .msi installer'
# -x jpackageImage: reuse the application folder built above.
Gradle :app:jpackageInstaller -x :app:jpackageImage -PinstallerType=msi

Say 'Building the portable .zip'
Compress-Archive -Path 'app\build\jpackage\image\HL7 Sender' -DestinationPath (
    Join-Path $out "hl7-sender-$version-windows-x64.zip") -Force

Say 'Done. Installers in app\build\jpackage\installer:'
Get-ChildItem $out | Format-Table Name, @{ Name = 'Size (MB)'; Expression = { [math]::Round($_.Length / 1MB, 1) } }

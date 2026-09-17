$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'JDK 17+ is required' }
$Gradle = if (Test-Path .\gradlew.bat) { '.\gradlew.bat' } elseif (Get-Command gradle -ErrorAction SilentlyContinue) { 'gradle' } else { throw 'Gradle 8.9+ or gradlew.bat is required' }
& $Gradle testDebugUnitTest
& $Gradle assembleDebug
New-Item -ItemType Directory -Force release | Out-Null
Copy-Item app\build\outputs\apk\debug\app-debug.apk release\QuantivueAI-Mobile-debug.apk
Copy-Item release\QuantivueAI-Mobile-debug.apk release\QuantivueAI-Mobile.apk
Get-FileHash release\QuantivueAI-Mobile.apk, release\QuantivueAI-Mobile-debug.apk -Algorithm SHA256 | Format-Table

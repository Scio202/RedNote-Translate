$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root = Join-Path $env:USERPROFILE 'android-toolchain'
$Dl   = Join-Path $Root 'downloads'
New-Item -ItemType Directory -Force -Path $Dl | Out-Null

function Get-File($url, $out) {
  if (Test-Path $out) { Write-Host "have $out"; return }
  Write-Host "downloading $url"
  Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
}

# --- JDK 17 (sdkmanager and AGP both need 17+) ---
$jdkZip = Join-Path $Dl 'jdk17.zip'
Get-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $jdkZip
if (-not (Test-Path (Join-Path $Root 'jdk'))) {
  Expand-Archive -Path $jdkZip -DestinationPath (Join-Path $Root 'jdk-tmp') -Force
  $inner = Get-ChildItem (Join-Path $Root 'jdk-tmp') -Directory | Select-Object -First 1
  Move-Item $inner.FullName (Join-Path $Root 'jdk')
  Remove-Item (Join-Path $Root 'jdk-tmp') -Recurse -Force
}
Write-Host "JDK ok"

# --- Gradle 8.9 ---
$gZip = Join-Path $Dl 'gradle.zip'
Get-File 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' $gZip
if (-not (Test-Path (Join-Path $Root 'gradle-8.9'))) {
  Expand-Archive -Path $gZip -DestinationPath $Root -Force
}
Write-Host "Gradle ok"

# --- Android SDK command-line tools ---
$sdk = Join-Path $Root 'sdk'
$cZip = Join-Path $Dl 'cmdline-tools.zip'
Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $cZip
$latest = Join-Path $sdk 'cmdline-tools\latest'
if (-not (Test-Path $latest)) {
  Expand-Archive -Path $cZip -DestinationPath (Join-Path $Root 'cl-tmp') -Force
  New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
  Move-Item (Join-Path $Root 'cl-tmp\cmdline-tools') $latest
  Remove-Item (Join-Path $Root 'cl-tmp') -Recurse -Force
}
Write-Host "cmdline-tools ok"

# --- SDK packages ---
$env:JAVA_HOME = Join-Path $Root 'jdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$sdkm = Join-Path $latest 'bin\sdkmanager.bat'

Write-Host "accepting licenses"
$yes = ("y`n" * 60)
$yes | & $sdkm --sdk_root=$sdk --licenses | Out-Null

Write-Host "installing platform + build-tools"
& $sdkm --sdk_root=$sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"

Write-Host "SETUP COMPLETE"

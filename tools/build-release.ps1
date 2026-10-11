$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path "$PSScriptRoot/..").Path
$env:JAVA_HOME = 'C:/heycyan/tools/android-build/java/jdk-17.0.16+8'
$env:ANDROID_HOME = 'C:/heycyan/tools/android-build/sdk'
$env:GRADLE_USER_HOME = "$PSScriptRoot/gradle-home"
$env:ANDROID_USER_HOME = "$PSScriptRoot/android-home"
Set-Location $taskRoot
if (!(Test-Path tools/release.jks)) {
 & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore tools/release.jks -storepass heycyan-local -keypass heycyan-local -alias heycyan -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=HeyCyan Live Local Build'
 if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
}
& ./gradlew.bat --no-daemon assembleRelease lintRelease
if ($LASTEXITCODE -ne 0) { throw 'Gradle build or lint failed' }
Copy-Item app/build/outputs/apk/release/app-release.apk dist/heycyan_live.apk

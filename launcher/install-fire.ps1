# Fire TV version of build-and-install.ps1: build the launcher's fire flavour, install it, and give it the
# HOME button through its HomeGuard accessibility service. Amazon's launcher (com.amazon.tv.launcher) is
# never disabled or uninstalled: Fire OS keeps HOME bound to it, and HomeGuard replaces its home window.
# The first run backs up package lists and settings to backup\fire-tv; restore-fire.ps1 undoes every change.
# Uses Android Studio's bundled JDK and the default SDK location unless JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\install-fire.ps1 -TvHost 192.168.1.60 [-BackupDir backup\fire-tv] [-ClockScreensaver]
#   -ClockScreensaver  also install the Clock screensaver (launcher/clock, its own package) and select it
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$BackupDir,
    [switch]$ClockScreensaver
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <fire tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot
$repo = Split-Path -Parent $PSScriptRoot
if (-not $BackupDir) { $BackupDir = Join-Path $repo 'backup\fire-tv' }
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
$tv = Join-Path $repo 'tools\tv.py'
if (-not (Test-Path $py)) { throw 'run: python -m venv tools\venv; tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt' }

if ("$(& $py -I $tv 'pm list features')" -notmatch 'amazon\.hardware\.fire_tv') {
    throw "$TvHost is not a Fire TV; use build-and-install.ps1"
}

# Back up once, before the first change; restore-fire.ps1 reads key-settings.txt.
if (-not (Test-Path (Join-Path $BackupDir 'key-settings.txt'))) {
    New-Item -ItemType Directory -Force $BackupDir | Out-Null
    $dumps = [ordered]@{
        'getprop.txt'               = 'getprop'
        'packages-all.txt'          = 'pm list packages'
        'packages-disabled.txt'     = 'pm list packages -d'
        'packages-3rdparty.txt'     = 'pm list packages -3'
        'settings-global.txt'       = 'settings list global'
        'settings-secure.txt'       = 'settings list secure'
        'settings-system.txt'       = 'settings list system'
        'dumpsys-accessibility.txt' = 'dumpsys accessibility'
        'key-settings.txt'          = 'for k in enabled_accessibility_services accessibility_enabled screensaver_components screensaver_enabled; do echo "$k=$(settings get secure $k)"; done; echo "alarm_manager_constants=$(settings get global alarm_manager_constants)"'
    }
    foreach ($file in $dumps.Keys) {
        & $py -I $tv $dumps[$file] | Out-File -Encoding utf8 (Join-Path $BackupDir $file)
        if ($LASTEXITCODE -ne 0) { throw "backup failed at $file; nothing was changed on the TV" }
    }
    Write-Host "Backed up to $BackupDir"
}

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

$tasks = @('assembleFireDebug')
if ($ClockScreensaver) { $tasks += ':clock:assembleDebug' }
.\gradlew.bat @tasks -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$apk = 'app\build\outputs\apk\fire\debug\app-fire-debug.apk'
& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I $tv 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -notmatch 'Success') { throw 'pm install failed; nothing else was changed' }

@(
    # Lets the Wi-Fi row show the network name.
    'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
    # Keeps app standby from deferring HomeGuard's alarm.
    'dumpsys deviceidle whitelist +com.gathin.tvhome >/dev/null; echo deviceidle-whitelisted',
    # Alarms normally fire no sooner than 5 s ahead; HomeGuard's launch must fire at once. Other constants stay.
    ('c=$(settings get global alarm_manager_constants); ' +
     'n=$(echo "$c" | tr "," "\n" | grep -v -e "^min_futurity=" -e "^null$" -e "^$" | tr "\n" "," | sed "s/,$//"); ' +
     'settings put global alarm_manager_constants "${n:+$n,}min_futurity=0"; ' +
     'echo "alarm_manager_constants=$(settings get global alarm_manager_constants)"'),
    # Add HomeGuard to the enabled accessibility services, keeping any others. Clearing the list first
    # makes the accessibility manager rebind it after the reinstall.
    ('s=$(settings get secure enabled_accessibility_services); ' +
     'n=$(echo "$s" | tr ":" "\n" | grep -v -e "^com.gathin.tvhome/" -e "^null$" -e "^$" | tr "\n" ":" | sed "s/:$//"); ' +
     'settings put secure enabled_accessibility_services ""; sleep 1; ' +
     'settings put secure enabled_accessibility_services "${n:+$n:}com.gathin.tvhome/.HomeGuard"; ' +
     'settings put secure accessibility_enabled 1; ' +
     'echo "enabled_accessibility_services=$(settings get secure enabled_accessibility_services)"'),
    'pm list packages -d | grep -q "^package:com.amazon.tv.launcher$" && echo "WARNING: com.amazon.tv.launcher is disabled" || echo "com.amazon.tv.launcher enabled"',
    'sleep 2; dumpsys accessibility | grep -E "Bound services|Enabled services"',
    # Open it the way HOME will from now on (am start would make a second, non-home instance). HomeGuard
    # only acts when Amazon's home comes to the front, so leave it for Fire's settings first.
    'am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_SETTINGS >/dev/null; sleep 2; input keyevent KEYCODE_HOME; sleep 3; dumpsys activity activities | grep -m1 mResumedActivity'
) | & $py -I $tv --batch

# Fire OS force-stops a screensaver's app when it ends, so the Clock lives in its own package
# (com.gathin.tvhome.clock): stopping it leaves HomeGuard running.
if ($ClockScreensaver) {
    & $py -I ..\tools\b64push.py 'clock\build\outputs\apk\debug\clock-debug.apk' /data/local/tmp/tvclock.apk
    if ($LASTEXITCODE -ne 0) { throw 'Clock APK upload failed' }
    @(
        'pm install -r /data/local/tmp/tvclock.apk; rm -f /data/local/tmp/tvclock.apk',
        'settings put secure screensaver_components com.gathin.tvhome.clock/com.gathin.tvhome.ClockDream; echo "screensaver_components=$(settings get secure screensaver_components)"'
    ) | & $py -I $tv --batch
}
Write-Host 'Installed. Press Home on the remote to see it; launcher\restore-fire.ps1 undoes everything.'

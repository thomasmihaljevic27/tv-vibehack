# Undoes launcher\install-fire.ps1 on a Fire TV: hands HOME back to Amazon's launcher and puts back
# every setting the install changed, using the values install-fire.ps1 saved to the backup folder
# before its first run. Neither script ever disables or uninstalls com.amazon.tv.launcher.
#
# Usage: launcher\restore-fire.ps1 -TvHost 192.168.1.60 [-BackupDir backup\fire-tv] [-KeepApp]
#   -KeepApp  leave com.gathin.tvhome installed (HOME still goes back to Amazon); by default it is uninstalled
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$BackupDir,
    [switch]$KeepApp
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <fire tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
$repo = Split-Path -Parent $PSScriptRoot
if (-not $BackupDir) { $BackupDir = Join-Path $repo 'backup\fire-tv' }
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
$tv = Join-Path $repo 'tools\tv.py'

# Settings as they were before the first install (key=value lines; "null" means unset).
$orig = @{}
$keyFile = Join-Path $BackupDir 'key-settings.txt'
if (Test-Path $keyFile) {
    Get-Content $keyFile | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $orig[$Matches[1]] = $Matches[2].Trim() } }
} else {
    Write-Warning "no backup at $keyFile; restoring Fire OS defaults (no accessibility services, no alarm constants)"
}
function Orig([string]$key, [string]$default) {
    if ($orig.ContainsKey($key) -and $orig[$key] -ne '') { return $orig[$key] } else { return $default }
}
$a11yEnabled = Orig 'accessibility_enabled' '0'
if ($a11yEnabled -eq 'null') { $a11yEnabled = '0' }
# Re-add min_futurity only if the TV had one of its own before.
$futurity = ''
if ((Orig 'alarm_manager_constants' 'null') -match '(?:^|,)min_futurity=([^,]+)') { $futurity = "min_futurity=$($Matches[1])" }
$saver = Orig 'screensaver_components' 'null'

$cmds = @(
    # Drop our HomeGuard from the enabled accessibility services, keeping any others.
    ('s=$(settings get secure enabled_accessibility_services); ' +
     'n=$(echo "$s" | tr ":" "\n" | grep -v -e "^com.gathin.tvhome/" -e "^null$" -e "^$" | tr "\n" ":" | sed "s/:$//"); ' +
     'if [ -n "$n" ]; then settings put secure enabled_accessibility_services "$n"; ' +
     'else settings delete secure enabled_accessibility_services >/dev/null; settings put secure accessibility_enabled @A11Y@; fi; ' +
     'echo "enabled_accessibility_services=$(settings get secure enabled_accessibility_services) accessibility_enabled=$(settings get secure accessibility_enabled)"').Replace('@A11Y@', $a11yEnabled),
    # Remove min_futurity=0 from the alarm constants, keeping any other constants.
    ('c=$(settings get global alarm_manager_constants); ' +
     'n=$(echo "$c" | tr "," "\n" | grep -v -e "^min_futurity=" -e "^null$" -e "^$" | tr "\n" "," | sed "s/,$//"); ' +
     'f="@FUT@"; [ -n "$f" ] && n="${n:+$n,}$f"; ' +
     'if [ -n "$n" ]; then settings put global alarm_manager_constants "$n"; else settings delete global alarm_manager_constants >/dev/null; fi; ' +
     'echo "alarm_manager_constants=$(settings get global alarm_manager_constants)"').Replace('@FUT@', $futurity),
    'dumpsys deviceidle whitelist -com.gathin.tvhome >/dev/null 2>&1; echo deviceidle-whitelist-removed',
    # Only if the Clock screensaver was picked: back to the screensaver from before.
    ('case "$(settings get secure screensaver_components)" in *com.gathin.tvhome*) ' +
     'if [ "@SAVER@" = null ]; then settings delete secure screensaver_components >/dev/null; ' +
     'else settings put secure screensaver_components "@SAVER@"; fi ;; esac; ' +
     'echo "screensaver_components=$(settings get secure screensaver_components)"').Replace('@SAVER@', $saver)
)
if ($KeepApp) {
    $cmds += 'pm revoke com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-revoked'
} else {
    $cmds += 'pm uninstall com.gathin.tvhome'
}
$cmds += @(
    'am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null; echo home-sent',
    'pm list packages -d | grep -q "^package:com.amazon.tv.launcher$" && echo "WARNING: com.amazon.tv.launcher is disabled" || echo "com.amazon.tv.launcher enabled"'
)
$cmds | & $py -I $tv --batch
if ($LASTEXITCODE -ne 0) { throw 'restore failed; run it again' }
Write-Host "Restored. HOME goes to Amazon's launcher again."

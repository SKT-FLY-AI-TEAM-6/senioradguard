# GuArDian device DB live viewer.
# Pulls the Room DB from the USB-connected phone every 3 seconds,
# prints to console AND writes an auto-refreshing HTML page
# (opens in the default browser on first run). Ctrl+C to stop.
#
# NOTE: keep this file ASCII-only. PowerShell 5.1 misreads BOM-less
# UTF-8 as the ANSI codepage, which silently corrupts Korean text
# (this actually happened - Korean lives in db_dump.py instead).
$adb = "C:\Users\013\android-tools\sdk\platform-tools\adb.exe"
$py  = "C:\Users\013\AppData\Local\Programs\Python\Python312\python.exe"
$dumpPy = Join-Path $PSScriptRoot "db_dump.py"

$tmp = Join-Path $env:TEMP "guardian-db-watch"
New-Item -ItemType Directory -Force $tmp | Out-Null
$db   = Join-Path $tmp "d.db"
$htmlOut = Join-Path $tmp "guardian-db.html"

chcp 65001 | Out-Null
$env:PYTHONIOENCODING = "utf-8"

$opened = $false
while ($true) {
    cmd /c "`"$adb`" exec-out run-as com.senioradguard.rg cat databases/senior_ad_guard.db > `"$db`"" 2>$null
    cmd /c "`"$adb`" exec-out run-as com.senioradguard.rg cat databases/senior_ad_guard.db-wal > `"$db-wal`"" 2>$null
    Clear-Host
    if ((Get-Item $db -ErrorAction SilentlyContinue).Length -gt 0) {
        & $py $dumpPy $db $htmlOut
        if (-not $opened) {
            $opened = $true
            Start-Process $htmlOut   # open the auto-refreshing page in the browser
        }
    } else {
        "Cannot read DB from the phone - check USB connection and app install."
    }
    Start-Sleep 3
}

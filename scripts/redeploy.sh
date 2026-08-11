#!/usr/bin/env bash
set -e
ADB="/c/Users/skdla/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERVICE="com.senioradguard/com.senioradguard.service.AdGuardAccessibilityService"
./gradlew installDebug
"$ADB" shell settings put secure enabled_accessibility_services "$SERVICE"
"$ADB" shell settings put secure accessibility_enabled 1
"$ADB" shell appops set com.senioradguard SYSTEM_ALERT_WINDOW allow
"$ADB" shell am start -n com.senioradguard/.MainActivity
echo "재배포 완료 — 접근성 서비스 활성화됨"

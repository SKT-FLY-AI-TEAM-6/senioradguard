#!/usr/bin/env bash
#
# 실기기 재배포. 순서가 중요하다 —
#   설치(=force-stop 발생) → 접근성 서비스 재활성화 → 앱 실행
# Android가 재설치 시 안전장치로 enabled_accessibility_services를 지우기 때문에,
# 반대 순서로 하면 설정에는 "켜짐"으로 보이는데 실제로는 이벤트를 못 받는다.
set -e

# Git Bash(MSYS)가 "com.pkg/com.pkg.Service"의 슬래시를 경로로 보고 변환해버린다.
export MSYS_NO_PATHCONV=1

# adb는 PATH → ANDROID_HOME 순으로 찾는다 (개발자마다 SDK 위치가 다르다)
if command -v adb >/dev/null 2>&1; then
  ADB="adb"
elif [ -n "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME/platform-tools/adb.exe" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb.exe"
else
  echo "adb를 찾을 수 없다. PATH에 넣거나 ANDROID_HOME을 설정할 것." >&2
  exit 1
fi

# debug 빌드는 applicationIdSuffix ".rg"가 붙는다 (스튜디오 라인과 나란히 설치용).
# 컴포넌트 표기는 "설치 패키지ID/클래스 전체 이름" — 클래스 쪽은 namespace 그대로다.
PKG="com.senioradguard.rg"
SERVICE="$PKG/com.senioradguard.service.AdGuardAccessibilityService"

./gradlew installDebug

# 이미 켜져 있던 다른 접근성 서비스를 지우지 않는다. 통째로 덮어쓰면 사용자가
# 쓰던 화면읽기·다른 광고 감지 앱이 조용히 꺼진다.
CURRENT="$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')"
case "$CURRENT" in
  null|"") MERGED="$SERVICE" ;;
  *"$SERVICE"*) MERGED="$CURRENT" ;;
  *) MERGED="$CURRENT:$SERVICE" ;;
esac

"$ADB" shell settings put secure enabled_accessibility_services "$MERGED"
"$ADB" shell settings put secure accessibility_enabled 1
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"$ADB" shell am start -n "$PKG/com.senioradguard.MainActivity"

echo "재배포 완료 — 접근성 서비스: $MERGED"

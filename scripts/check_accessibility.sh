#!/usr/bin/env bash
# 설치 후 접근성 서비스가 실제로 등록·바인드됐는지 확인한다.
#
# 재설치나 force-stop을 하면 Android가 안전장치로 enabled_accessibility_services를
# 지운다. 설정 화면에는 "켜짐"으로 보여도 이벤트를 못 받는 상태가 되므로,
# 설치 직후에는 반드시 이 확인을 거쳐야 한다.
set -u

# debug 빌드는 applicationIdSuffix ".rg"가 붙는다 (redeploy.sh와 동일해야 한다).
SERVICE="com.senioradguard.rg/com.senioradguard.service.AdGuardAccessibilityService"
ENABLED=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
FLAG=$(adb shell settings get secure accessibility_enabled | tr -d '\r')

fail=0

case ":$ENABLED:" in
  *":$SERVICE:"*) echo "OK   접근성 목록에 등록됨" ;;
  *)
    echo "경고 접근성 목록에 없음 — 광고 감지가 전혀 동작하지 않습니다."
    echo "     해결: adb shell settings put secure enabled_accessibility_services \"$SERVICE\""
    echo "           adb shell settings put secure accessibility_enabled 1"
    fail=1
    ;;
esac

if [ "$FLAG" = "1" ]; then
  echo "OK   accessibility_enabled=1"
else
  echo "경고 accessibility_enabled=$FLAG (1이어야 함)"
  fail=1
fi

# 설정값과 별개로 시스템이 실제로 바인드했는지 본다.
# 설정엔 켜짐인데 서비스만 죽어 있는 경우가 실제로 생긴다.
if adb shell dumpsys accessibility | grep -q "Service\[label=GuArDian"; then
  echo "OK   시스템이 서비스를 바인드함"
else
  echo "경고 바인드되지 않음 — dumpsys accessibility의 Crashed services를 확인하세요."
  fail=1
fi

exit $fail

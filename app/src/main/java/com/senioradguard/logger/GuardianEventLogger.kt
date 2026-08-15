package com.senioradguard.logger

import com.senioradguard.remote.FirebaseRepo

/**
 * 신규 기능(도메인 차단·DBD 설치 차단·검색 위험도)의 원격 기록.
 *
 * 이 세 종류만 원격에 남긴다. **도메인명·URL·화면 내용은 절대 싣지 않는다** —
 * 어르신이 어디를 보고 있었는지는 보호자에게도 넘기지 않는다는 원칙
 * ([AdEventLogger] 주석 참고)을 신규 기능에서도 그대로 지킨다. 보호자가 알아야
 * 할 것은 "위험한 일이 있었고 앱이 개입했다"는 사실뿐이다.
 *
 * 기존 [AdEventLogger]에 함수를 더하지 않고 파일을 새로 둔 것은 의도다 —
 * phase4 코드는 한 줄도 고치지 않는다는 병합 원칙 때문이다. 전송 경로는
 * 같은 [FirebaseRepo]를 그대로 쓴다.
 */
object GuardianEventLogger {

    /** 얹을 것 1 — 블랙리스트 도메인 도착을 경고했다. */
    const val TYPE_DOMAIN_BLOCKED = "DOMAIN_BLOCKED"

    /** 얹을 것 2 — 스토어를 거치지 않은 APK 설치 화면에 경고를 덮었다. */
    const val TYPE_APK_INSTALL_WARNING = "APK_INSTALL_WARNING"

    /** 얹을 것 3 — 검색 결과에서 최고 위험 등급이 나왔다. 이 등급만 승격한다. */
    const val TYPE_SEARCH_RISK_DETECTED = "SEARCH_RISK_DETECTED"

    fun logDomainBlocked() = send(TYPE_DOMAIN_BLOCKED)

    fun logApkInstallWarning() = send(TYPE_APK_INSTALL_WARNING)

    fun logSearchRiskDetected() = send(TYPE_SEARCH_RISK_DETECTED)

    /**
     * 이벤트 스키마는 기존 events/{userId} 노드를 그대로 쓴다 — 보호자 화면이
     * 이미 그 노드를 구독하고 있어, 새 노드를 만들면 표시 코드도 새로 필요하다.
     * type은 adText 자리에 실린다. appPackage를 비우는 것도 원칙의 일부다
     * (패키지명으로도 어르신의 행동이 유추된다).
     */
    private fun send(type: String) {
        FirebaseRepo.logEvent(appPackage = "", adText = type, action = "warned", layer = 3)
    }
}

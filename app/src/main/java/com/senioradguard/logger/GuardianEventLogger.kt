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

    /**
     * 같은 종류를 이 시간 안에 다시 남기지 않는다.
     *
     * 실측(2026-08-18, 실기기 + 서버 조회)에서 한 번의 사건이 여러 줄로 쌓였다 —
     * 검색 한 번에 SEARCH_RISK_DETECTED 4건, 설치 화면 한 번에
     * APK_INSTALL_WARNING 3건. 원인은 종류마다 다르다. 검색 위험은 결과 칸마다
     * 승격이 일어나고(위험한 사이트가 3개면 3번), 설치 경고는 화면이 다시 뜰
     * 때마다 발동한다.
     *
     * 그런데 보호자에게 필요한 정보는 어느 쪽이든 **"그런 일이 있었다"** 하나다.
     * 이 기록에는 도메인도 앱 이름도 싣지 않으므로(위 주석의 원칙) 3건과 1건이
     * 보호자에게 주는 정보량이 정확히 같고, 여러 줄은 정작 중요한 다른 사건을
     * 목록 아래로 밀어낼 뿐이다. 그래서 세는 대신 묶는다.
     *
     * 1분은 "같은 사건"으로 볼 만한 길이다. 진짜로 다른 사건이 1분 안에 두 번
     * 일어나면 한 건을 놓치지만, 그 경우에도 보호자는 첫 건을 통해 이미 알게 된다.
     */
    private const val DEDUP_WINDOW_MS = 60_000L

    /** 종류별 마지막 전송 시각. 여러 스레드가 부르므로 잠그고 만진다 */
    private val lastSentAt = mutableMapOf<String, Long>()

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
        // 벽시계가 아니라 부팅 이후 경과 시간을 쓴다 — 사용자가 시간대를 바꾸거나
        // 시계가 보정되면 벽시계는 뒤로 갈 수 있고, 그러면 창 계산이 음수가 된다.
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastSentAt) {
            val last = lastSentAt[type]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            lastSentAt[type] = now
        }
        FirebaseRepo.logEvent(appPackage = "", adText = type, action = "warned", layer = 3)
    }
}

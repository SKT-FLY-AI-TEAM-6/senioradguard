package com.senioradguard.logger

import android.content.Context
import com.senioradguard.remote.FirebaseRepo
import com.senioradguard.risk.RiskLevel

/**
 * 차단 내역을 남긴다. 보호자 모드가 이 기록을 실시간으로 구독해 보여준다.
 *
 * **화면에 뜬 글자는 올리지 않는다.** 예전에는 광고 문구를 마스킹해서 보냈는데,
 * 마스킹은 전화·카드 같은 알려진 패턴만 지울 뿐 어르신이 무엇을 읽고 있었는지는
 * 그대로 남는다. 보호자가 알아야 할 것은 "언제 어디서 무슨 종류가 걸렸는가"이지
 * 본문이 아니다. 지금 올라가는 것은 위험등급·유형·차단여부·시각·출처뿐이다.
 */
object AdEventLogger {

    fun init(context: Context) {
        FirebaseRepo.init(context)
    }

    /** Layer 1·2가 광고를 표시했을 때. */
    fun logAdMarked(source: String, layer: Int, risk: RiskLevel, count: Int) {
        FirebaseRepo.logEvent(
            appPackage = source,
            type = if (layer == 1) TYPE_AD_LABELED else TYPE_AD_GUESSED,
            risk = risk,
            blocked = false,
            layer = layer,
            count = count
        )
    }

    /** URL 차단 목록에 걸렸을 때. */
    fun logBlockedDomain(host: String, blocked: Boolean) {
        FirebaseRepo.logEvent(
            appPackage = host,
            type = TYPE_BLOCKED_DOMAIN,
            risk = RiskLevel.HIGH,
            blocked = blocked,
            layer = 3
        )
    }

    /** Layer 3 — 스토어 강제 이동 감지. */
    fun logStoreRedirect(storePackage: String) {
        FirebaseRepo.logEvent(
            appPackage = storePackage,
            type = TYPE_STORE_REDIRECT,
            risk = RiskLevel.MEDIUM,
            blocked = false,
            layer = 3
        )
    }

    /** Layer 3 — 설치 유도 버튼 클릭에 경고를 띄웠을 때. */
    fun logInstallBlocked(packageName: String) {
        FirebaseRepo.logEvent(
            appPackage = packageName,
            type = TYPE_INSTALL_BLOCKED,
            risk = RiskLevel.HIGH,
            blocked = true,
            layer = 3
        )
    }

    /** Layer 3 — 사용자가 경고를 무시하고 "그냥 보기"를 골랐을 때. */
    fun logIgnored(packageName: String) {
        FirebaseRepo.logEvent(
            appPackage = packageName,
            type = TYPE_IGNORED,
            risk = RiskLevel.MEDIUM,
            blocked = false,
            layer = 3
        )
    }

    const val TYPE_AD_LABELED = "ad_labeled"
    const val TYPE_AD_GUESSED = "ad_guessed"
    const val TYPE_BLOCKED_DOMAIN = "blocked_domain"
    const val TYPE_STORE_REDIRECT = "store_redirect"
    const val TYPE_INSTALL_BLOCKED = "install_blocked"
    const val TYPE_IGNORED = "ignored"
}

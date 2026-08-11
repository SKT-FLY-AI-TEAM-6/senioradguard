package com.senioradguard.logger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.senioradguard.notification.KakaoNotifier
import com.senioradguard.notification.PendingNotificationQueue

/**
 * AdEventLogger
 *
 * 광고 감지 이벤트를 로컬 저장 + 카카오 보호자 알림 전송.
 * AccessibilityService에서 직접 호출됩니다.
 */
object AdEventLogger {

    private lateinit var notifier: KakaoNotifier
    private val scope = CoroutineScope(Dispatchers.IO)

    fun init(context: Context) {
        notifier = KakaoNotifier(context)
        PendingNotificationQueue.init(context)
    }

    /** 일반 광고 감지 이벤트 */
    fun log(packageName: String, score: Float) {
        val detail = "출처: $packageName (신뢰도: ${(score * 100).toInt()}%)"
        saveLocal("ad_warning", detail, blocked = true)
        sendNotification("ad_warning", detail, blocked = true)
    }

    /** Play Store 강제 이동 감지 */
    fun logStoreRedirect(storePackage: String) {
        val detail = "앱스토어: $storePackage"
        saveLocal("store_redirect", detail, blocked = false)
        sendNotification("store_redirect", detail, blocked = false)
    }

    /** 앱 설치 버튼 클릭 차단 */
    fun logInstallBlocked(buttonText: String) {
        saveLocal("app_install_blocked", buttonText, blocked = true)
        sendNotification("app_install_blocked", buttonText, blocked = true)
    }

    // ──────────────────────────────────────

    private fun sendNotification(eventType: String, detail: String, blocked: Boolean) {
        if (!::notifier.isInitialized) return
        if (!notifier.isGuardianSet()) return  // 보호자 미설정 시 스킵

        scope.launch {
            notifier.notify(eventType, detail, blocked)
        }
    }

    private fun saveLocal(eventType: String, detail: String, blocked: Boolean) {
        // TODO: Room DB에 저장 (차단 내역 히스토리)
        // EventEntity(type=eventType, detail=detail, blocked=blocked, time=now)
    }
}

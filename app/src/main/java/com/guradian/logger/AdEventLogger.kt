package com.guradian.logger

import android.content.Context
import android.util.Log

/**
 * AdEventLogger
 *
 * 광고 감지 이벤트를 기록한다. AccessibilityService에서 직접 호출된다.
 *
 * ## 원본에서 달라진 점 — 보호자 알림 전송이 빠졌다
 * senioradguard에서는 이 객체가 이벤트를 카카오 보호자 알림으로 내보냈다.
 * 카카오 비즈 앱 검수가 아직 승인되지 않아 호출해도 실패하므로 이 저장소에서는
 * 전송 경로를 통째로 걷어냈다. 남은 것은 이벤트가 났다는 사실을 로그로 남기는
 * 것뿐이고, 호출부(InstallGuard)는 그대로 둔다 — 나중에 보호자 알림이 붙을 때
 * 여기 구현만 바꾸면 되도록.
 *
 * ⚠️ 화면 텍스트 원문과 URL 전문은 여기 들어오지 않는다. 지금은 로그만 찍지만,
 *    나중에 서버 전송이 붙어도 원문이 새어나갈 통로가 없어야 하므로 시그니처에
 *    원문을 받지 않는 성질을 유지할 것.
 */
object AdEventLogger {

    private const val TAG = "GurADian"

    /**
     * 보관된다면 어디에 보관할지 정하는 자리. 지금은 아무 데도 저장하지 않는다.
     * (이벤트 히스토리는 서버·DB 작업에 속한다)
     */
    @Suppress("UNUSED_PARAMETER")
    fun init(context: Context) = Unit

    /** 일반 광고 감지 이벤트 */
    fun log(packageName: String, score: Float) {
        record("ad_warning", "출처: $packageName (신뢰도: ${(score * 100).toInt()}%)")
    }

    /** Play Store 강제 이동 감지 */
    fun logStoreRedirect(storePackage: String) {
        record("store_redirect", "앱스토어: $storePackage")
    }

    /** 앱 설치 버튼 클릭 차단 */
    fun logInstallBlocked(buttonText: String) {
        record("app_install_blocked", buttonText)
    }

    private fun record(eventType: String, detail: String) {
        Log.i(TAG, "event=$eventType $detail")
    }
}

package com.senioradguard

import android.app.Application
import com.kakao.sdk.common.KakaoSdk
import com.senioradguard.logger.AdEventLogger

class SeniorAdGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)

        // AccessibilityService도 같은 프로세스라 여기서 한 번 초기화하면 된다.
        // 이 호출이 없으면 AdEventLogger.notifier와 PendingNotificationQueue.prefs가
        // 초기화되지 않아, 보호자 알림이 조용히 아무 일도 하지 않고 반환된다.
        AdEventLogger.init(this)
    }
}

package com.guradian

import android.app.Application
import com.guradian.logger.AdEventLogger

class GuardianApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // AccessibilityService도 같은 프로세스라 여기서 한 번 초기화하면 된다.
        // (원본은 여기서 카카오 SDK도 초기화했다 — 보호자 알림은 이 저장소의 범위 밖)
        AdEventLogger.init(this)
    }
}

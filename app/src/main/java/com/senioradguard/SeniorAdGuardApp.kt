package com.senioradguard

import android.app.Application
import com.senioradguard.logger.AdEventLogger

class SeniorAdGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // AccessibilityService도 같은 프로세스라 여기서 한 번 초기화하면 된다.
        // 이 호출이 없으면 FirebaseRepo가 초기화되지 않아 차단 내역이 조용히
        // 사라진다(예전에 실제로 그랬다).
        AdEventLogger.init(this)
    }
}

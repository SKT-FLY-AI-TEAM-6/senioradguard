package com.senioradguard

import android.app.Application
import com.senioradguard.detector.BlacklistSeeder
import com.senioradguard.logger.AdEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SeniorAdGuardApp : Application() {

    /**
     * 앱이 살아있는 동안 유지되는 스코프.
     *
     * 초기 차단 목록 삽입을 액티비티의 lifecycleScope에서 돌렸더니 "Job was
     * cancelled"로 끊겼다. 14만 행을 넣는 데 몇 초가 걸리는데 그 사이 액티비티가
     * 사라지면(화면 회전·종료) 코루틴도 함께 죽는다. 앱 전체에 한 번만 하는
     * 작업이므로 화면 수명에 묶으면 안 된다.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // AccessibilityService도 같은 프로세스라 여기서 한 번 초기화하면 된다.
        // 이 호출이 없으면 FamilyRepo가 초기화되지 않아 차단 내역이 조용히
        // 사라진다(예전에 실제로 그랬다).
        AdEventLogger.init(this)

        scope.launch { BlacklistSeeder.seedIfNeeded(this@SeniorAdGuardApp) }
    }
}

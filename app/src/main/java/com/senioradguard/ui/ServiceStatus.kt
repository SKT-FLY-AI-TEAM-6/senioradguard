package com.senioradguard.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.senioradguard.service.AdGuardAccessibilityService

/**
 * 접근성 서비스가 실제로 살아 있는지 확인한다.
 *
 * 두 가지를 따로 봐야 한다. 설정에는 "켜짐"으로 남아 있는데 서비스 객체는 죽어
 * 있는 상태가 실제로 생기기 때문이다(제조사 절전이 프로세스를 얼리거나 죽인 경우).
 * 이때 앱이 "지켜보고 있어요"라고 말하면 사용자는 보호받는다고 믿는다 — 이 앱에서
 * 가장 위험한 실패 방식이라 두 신호를 구분해 다르게 안내한다.
 */
object ServiceStatus {

    enum class State {
        /** 정상 — 설정도 켜져 있고 서비스도 연결돼 있다 */
        RUNNING,

        /** 접근성 설정에서 꺼져 있다 (재설치·강제 종료 후 시스템이 지우기도 한다) */
        DISABLED,

        /** 설정은 켜져 있는데 서비스가 연결돼 있지 않다 */
        ENABLED_BUT_DEAD
    }

    fun current(context: Context): State = when {
        !isEnabledInSettings(context) -> State.DISABLED
        !AdGuardAccessibilityService.isConnected -> State.ENABLED_BUT_DEAD
        else -> State.RUNNING
    }

    private fun isEnabledInSettings(context: Context): Boolean {
        val setting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val component = ComponentName(
            context.packageName,
            AdGuardAccessibilityService::class.java.name
        ).flattenToString()
        return containsService(setting, component)
    }

    /**
     * 설정값은 ':'로 이어붙인 컴포넌트 목록이다. 단순 contains로 보면 다른 앱의
     * 컴포넌트 이름에 우리 이름이 부분 문자열로 들어 있을 때 오판하므로,
     * 항목 단위로 정확히 비교한다.
     */
    internal fun containsService(setting: String?, component: String): Boolean {
        if (setting.isNullOrEmpty()) return false
        return setting.split(':').any { it.trim().equals(component, ignoreCase = true) }
    }

    /** 접근성 설정 화면을 연다. 사용자가 직접 켜야 하고, 앱이 대신 켤 수는 없다. */
    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

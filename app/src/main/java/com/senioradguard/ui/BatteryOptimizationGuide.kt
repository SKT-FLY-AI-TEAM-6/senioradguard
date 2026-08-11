package com.senioradguard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 배터리 최적화 예외 상태 확인과 설정 화면 이동.
 *
 * 왜 필요한가: 제조사 절전 기능(삼성 Freecess 등)이 백그라운드 앱 프로세스를
 * 통째로 얼린다. 실기기 로그에서 확인된 증상:
 *
 *     FreecessController: com.senioradguard (state: Initial -> Frozen, Reason: Bg)
 *     FreecessController: FZ : com.senioradguard(10367) reason: Bg
 *
 * 얼어붙은 프로세스는 onAccessibilityEvent를 실행하지 못하므로 광고 감지가
 * 조용히 멈춘다. 접근성 설정에는 여전히 "켜짐"으로 보여서 사용자는 보호받고
 * 있다고 믿게 된다 — 이 침묵이 가장 위험한 부분이라 앱이 먼저 알려줘야 한다.
 */
object BatteryOptimizationGuide {

    /** 배터리 최적화 예외가 걸려 있으면 true (= 얼지 않고 계속 동작). */
    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 시스템 허용 창을 띄운다. "허용"을 누르면 예외에 등록된다.
     *
     * 이 인텐트는 일부 기기/롬에서 처리기가 없을 수 있어, 그럴 때는 배터리 최적화
     * 목록 화면으로, 그것도 없으면 앱 상세 설정으로 단계적으로 물러난다.
     * 사용자를 막다른 곳에 두지 않는 것이 목적이다.
     */
    @SuppressLint("BatteryLife")
    fun requestExemption(context: Context) {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        if (direct.resolveActivity(context.packageManager) != null) {
            context.startActivity(direct)
            return
        }

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (list.resolveActivity(context.packageManager) != null) {
            context.startActivity(list)
            return
        }

        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}

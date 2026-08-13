package com.guradian.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * OverlayManager
 *
 * 광고 감지 시 화면 위에 경고 오버레이를 표시합니다.
 * 노인 사용자를 위해:
 *   - 큰 글씨 (24sp)
 *   - 굵은 버튼 2개만 (뒤로가기 / 무시)
 *   - 빨간 배경으로 즉각 인지 가능
 *
 * "뒤로 가기" 버튼은 2단계로 동작합니다:
 *   1단계: 즉시 GLOBAL_ACTION_BACK 실행
 *   2단계: 1.5초 후에도 같은 패키지가 여전히 포그라운드면 (뒤로가기가 안 먹힌 경우)
 *          GLOBAL_ACTION_HOME을 자동 실행해 강제로 빠져나옴
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    private val handler = Handler(Looper.getMainLooper())
    private var homeFallbackRunnable: Runnable? = null

    fun showWarning(
        message: String,
        packageName: String,
        onConfirm: () -> Unit,   // "무시하기" — 사용자가 계속 진행
        onBlock: () -> Unit,     // "뒤로 가기" 1단계 — GLOBAL_ACTION_BACK
        currentForegroundPackage: () -> String?,
        onForceHome: () -> Unit  // "뒤로 가기" 2단계 — GLOBAL_ACTION_HOME
    ) {
        if (overlayView != null) return // 이미 표시 중이면 중복 방지

        val layout = buildWarningLayout(
            message = message,
            packageName = packageName,
            onConfirm = onConfirm,
            onBlock = onBlock,
            currentForegroundPackage = currentForegroundPackage,
            onForceHome = onForceHome
        )
        val params = buildWindowParams()

        overlayView = layout
        windowManager.addView(layout, params)
    }

    fun dismiss() {
        cancelHomeFallback()
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
            overlayView = null
        }
    }

    private fun cancelHomeFallback() {
        homeFallbackRunnable?.let { handler.removeCallbacks(it) }
        homeFallbackRunnable = null
    }

    // ──────────────────────────────────────
    // UI 구성 (프로그래매틱 레이아웃 — XML 없이)
    // ──────────────────────────────────────

    private fun buildWarningLayout(
        message: String,
        packageName: String,
        onConfirm: () -> Unit,
        onBlock: () -> Unit,
        currentForegroundPackage: () -> String?,
        onForceHome: () -> Unit
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD000000.toInt()) // 반투명 검정 배경
            setPadding(48, 60, 48, 60)
            gravity = Gravity.CENTER
        }

        // 경고 아이콘 텍스트
        val icon = TextView(context).apply {
            text = "⚠️"
            textSize = 56f
            gravity = Gravity.CENTER
        }

        // 경고 메시지 — 큰 글씨
        val msgView = TextView(context).apply {
            text = message
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 40)
            lineHeight = (textSize * 1.5f).toInt()
        }

        // 버튼 컨테이너
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // "뒤로 가기" 버튼 (기본 선택 — 빨간색)
        val blockBtn = Button(context).apply {
            text = "뒤로 가기"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFD32F2F.toInt())
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                dismiss()
                onBlock() // 1단계: 즉시 뒤로 가기

                // 2단계: 1.5초 후에도 같은 앱이 그대로 떠 있으면 홈으로 강제 이동
                val runnable = Runnable {
                    if (currentForegroundPackage() == packageName) {
                        dismiss() // 그 사이 새로 뜬 오버레이가 있다면 함께 닫는다
                        onForceHome()
                    }
                    homeFallbackRunnable = null
                }
                homeFallbackRunnable = runnable
                handler.postDelayed(runnable, HOME_FALLBACK_DELAY_MS)
            }
        }

        // "무시하기" 버튼 (회색 — 덜 강조)
        val confirmBtn = Button(context).apply {
            text = "무시하기"
            textSize = 18f
            setTextColor(0xFF888888.toInt())
            setBackgroundColor(0xFF333333.toInt())
            setPadding(36, 24, 36, 24)
            setOnClickListener {
                cancelHomeFallback() // 무시하기를 누르면 예약된 2단계 홈 이동도 취소
                dismiss()
                onConfirm()
            }
        }

        // 마진 설정
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(16, 0, 16, 0) }

        btnRow.addView(blockBtn, lp)
        btnRow.addView(confirmBtn, lp)

        root.addView(icon)
        root.addView(msgView)
        root.addView(btnRow)

        return root
    }

    private fun buildWindowParams(): WindowManager.LayoutParams {
        val type = overlayWindowType()

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    companion object {
        private const val HOME_FALLBACK_DELAY_MS = 1500L
    }
}

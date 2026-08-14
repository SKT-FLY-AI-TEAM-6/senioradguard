package com.senioradguard.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 강제 이동 복귀 안내 — 화면 아래 큰 버튼 두 개("그냥 두기"/"뒤로 가기").
 *
 * ad_claude_fable의 showControls 이식. 광고·팝업이 사용자를 다른 사이트로
 * 끌고 갔을 때 12초간 뜬다. 가림막(광고 판정)과 달리 화면을 덮지 않아
 * 페이지는 그대로 보고 만질 수 있다 — 판정이 아니라 "돌아갈 길"만 준다.
 */
class BackPromptOverlay(private val service: AccessibilityService) {

    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private var bar: LinearLayout? = null

    fun isShowing(): Boolean = bar != null

    fun show(onStay: () -> Unit, onBack: () -> Unit) {
        if (bar != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,   // 버튼만큼만 차지 — 나머지는 그대로 터치된다
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(90)
        }
        bar = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(bigButton("그냥 두기", "#4A4A4A", onStay))
            addView(bigButton("뒤로 가기", "#2179FD", onBack))
        }
        runCatching { windowManager.addView(bar, params) }.onFailure { bar = null }
    }

    fun hide() {
        bar?.let { runCatching { windowManager.removeView(it) } }
        bar = null
    }

    private fun bigButton(label: String, color: String, onTap: () -> Unit) =
        TextView(service).apply {
            text = label
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(18), dp(26), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
        }

    private fun dp(v: Int): Int =
        (v * service.resources.displayMetrics.density).toInt()
}

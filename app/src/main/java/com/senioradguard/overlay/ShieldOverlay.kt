package com.senioradguard.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 광고발 페이지 진입 시 판정이 끝날 때까지 화면을 덮는 가림막.
 *
 * 페이지는 뒤에서 로드되지만 터치가 페이지에 도달하지 않으므로, 다운로드
 * 확인 → 파일 열기 → 설치 허용으로 이어지는 유도가 첫 터치부터 성립하지
 * 않는다. 피해는 페이지를 보는 순간이 아니라 입력·승인 순간에 발생한다.
 *
 * 정책상 가장 공격적인 오버레이이므로 두 가지를 지킨다.
 * 1. "차단"이 아니라 "확인 중 안내"다 — 판정이 끝나면 즉시 걷는다
 * 2. 호출부(서비스)가 최대 표시 시간을 강제한다 — 어떤 경로로든 가림막이
 *    화면에 눌러붙는 일은 없어야 한다
 *
 * AdBorderOverlay와 같은 TYPE_ACCESSIBILITY_OVERLAY지만 FLAG_NOT_TOUCHABLE을
 * 주지 않는다 — 터치를 삼키는 것이 이 창의 존재 이유다. 접근성 서비스
 * 자신의 컨텍스트로 만들어야 한다(applicationContext는 창 토큰이 없어
 * BadTokenException).
 */
class ShieldOverlay(private val context: Context) {

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var root: LinearLayout? = null
    private var iconView: TextView? = null
    private var titleView: TextView? = null
    private var subView: TextView? = null
    private var buttonRow: LinearLayout? = null

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(icon: String, title: String, sub: String) {
        root?.let {
            update(icon, title, sub)
            return
        }

        val titleText = TextView(context).apply {
            textSize = 40f
            gravity = Gravity.CENTER
            text = icon
        }
        val mainText = TextView(context).apply {
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(8))
            text = title
        }
        val subText = TextView(context).apply {
            textSize = 19f
            setTextColor(Color.parseColor("#CCCCCC"))
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            text = sub
        }

        // 판정 결과에서 쓰는 큰 버튼 두 개. 평소엔 감춰 둔다.
        // 루트가 터치를 삼켜도 클릭 가능한 자식이 먼저 받으므로 버튼은 눌린다.
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
            setPadding(0, dp(28), 0, 0)
        }

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FA101418"))
            addView(titleText)
            addView(mainText)
            addView(subText)
            addView(buttons)
            // 이 창의 존재 이유 — 아래 페이지로 가는 모든 터치를 삼킨다
            setOnTouchListener { _, _ -> true }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // 터치는 받되(NOT_TOUCHABLE 없음) 키 입력은 아래로 — 뒤로 가기가 살아 있어야
            // 가림막 중에도 사용자가 스스로 빠져나올 수 있다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        runCatching { windowManager.addView(view, params) }.onSuccess {
            root = view
            iconView = titleText
            titleView = mainText
            subView = subText
            buttonRow = buttons
        }
    }

    fun update(icon: String, title: String, sub: String) {
        iconView?.text = icon
        titleView?.text = title
        subView?.text = sub
        buttonRow?.visibility = android.view.View.GONE
    }

    /**
     * 판정 결과 + 선택 버튼. 중위험 확인과 미분석 안내에 쓴다.
     * 어르신 기준: 권장 행동(복귀)이 크고 진하게 위, 나머지는 회색으로 아래.
     */
    fun showChoice(
        icon: String,
        title: String,
        sub: String,
        primaryLabel: String,
        onPrimary: () -> Unit,
        secondaryLabel: String,
        onSecondary: () -> Unit
    ) {
        if (root == null) show(icon, title, sub)
        iconView?.text = icon
        titleView?.text = title
        subView?.text = sub
        val row = buttonRow ?: return
        row.removeAllViews()
        row.addView(bigButton(primaryLabel, "#D32F2F", onPrimary))
        row.addView(bigButton(secondaryLabel, "#4A4A4A", onSecondary))
        row.visibility = android.view.View.VISIBLE
    }

    private fun bigButton(label: String, color: String, onTap: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(18), dp(30), dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(8)) }
        }

    fun dismiss() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        titleView = null
        subView = null
        iconView = null
        buttonRow = null
    }
}

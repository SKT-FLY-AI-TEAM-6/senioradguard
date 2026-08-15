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
 * 버튼이 하나뿐인 전체화면 경고. 악성 도메인 도착·DBD APK 설치 화면에서 쓴다.
 *
 * [ShieldOverlay]와 같은 TYPE_ACCESSIBILITY_OVERLAY 전체화면이지만 역할이 다르다 —
 * 가림막은 "확인 중" 안내이고 판정이 끝나면 걷히는 반면, 이 창은 **판정이 이미
 * 끝난(고위험 확정) 화면**에 뜬다. 그래서 선택지를 주지 않는다. 알려진 사기
 * 사이트나 출처 불명 APK 설치에 "그냥 보기/그래도 설치"를 두면 그 버튼이 있다는
 * 사실만으로 어르신은 눌러도 되는 것으로 읽는다 (phase6 실측 결론 그대로).
 *
 * 뒤로 가기 키는 살려 둔다(FLAG_NOT_FOCUSABLE) — 버튼을 못 찾는 사용자도
 * 물리 뒤로 가기로 스스로 빠져나올 수 있어야 한다.
 *
 * ShieldOverlay와 마찬가지로 접근성 서비스 자신의 컨텍스트로 만들어야 한다
 * (applicationContext는 창 토큰이 없어 BadTokenException).
 */
class GuardAlertOverlay(private val context: Context) {

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var root: LinearLayout? = null

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /**
     * 경고를 띄운다. 이미 떠 있으면 내용만 갈아끼운다.
     *
     * @param onButton 유일한 버튼(복귀)의 동작. 창을 걷는 것은 호출부 몫이 아니라
     *        여기서 함께 한다 — 버튼을 눌렀는데 경고가 남아 있으면 안 된다.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(icon: String, title: String, sub: String, buttonLabel: String, onButton: () -> Unit) {
        dismiss()

        val iconText = TextView(context).apply {
            textSize = 48f
            gravity = Gravity.CENTER
            text = icon
        }
        val titleText = TextView(context).apply {
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(8))
            text = title
        }
        val subText = TextView(context).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#DDDDDD"))
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            text = sub
        }
        val button = TextView(context).apply {
            text = buttonLabel
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(34), dp(20), dp(34), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F"))
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener {
                dismiss()
                onButton()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(30), 0, 0) }
        }

        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FA181014"))
            addView(iconText)
            addView(titleText)
            addView(subText)
            addView(button)
            // 아래 화면(사기 페이지·설치 버튼)으로 가는 모든 터치를 삼킨다
            setOnTouchListener { _, _ -> true }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        runCatching { windowManager.addView(view, params) }.onSuccess { root = view }
    }

    fun dismiss() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }
}

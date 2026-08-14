package com.guradian.serp

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 검색 결과마다 위험도 배지를 그리는 비차단 오버레이.
 *
 * ## 광고 테두리와 **별도의 창**이다
 * 합치지 않은 이유는 소유권이다. 광고 감지(task 1)와 검색 결과 위험도는 다른
 * 사람이 다른 속도로 고치는 기능이고, 한 창을 공유하면 한쪽 변경이 다른 쪽 표시를
 * 지운다. 창을 나누면 서로의 코드를 건드리지 않는다.
 *
 * ## FLAG_NOT_TOUCHABLE은 절대 제거하지 않는다
 * 터치를 통과시켜 사용자의 선택을 일절 막지 않는다. 위험하다고 알릴 뿐 못 누르게
 * 하지는 않는다 — 구글 정책이기도 하고, 정말 그 사이트에 가려던 사용자를 가두면
 * 다음부터는 앱을 꺼버린다.
 *
 * ## '안전'에는 배지를 붙이지 않는다
 * 결과 열 개에 전부 무언가 붙으면 화면이 읽을 수 없게 되고, 다 붙어 있으면 아무
 * 의미도 전달되지 않는다. **초록 테두리만 얇게** 두르고 글자는 위험·주의에만 붙인다.
 */
class SerpBadgeOverlay(private val context: Context) {

    private companion object {
        /** 이보다 얇은 조각에는 배지를 넣을 수 없다. 테두리만 그린다. */
        const val BADGE_MIN_HEIGHT_DP = 56

        /** 위험 등급별 테두리 두께. 위험이 눈에 먼저 들어와야 한다. */
        const val STROKE_HIGH_DP = 5
        const val STROKE_LOW_DP = 2
    }

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var overlay: FrameLayout? = null
    private var shown: List<Pair<Rect, SerpVerdict>> = emptyList()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** 지금 표시 중인 판정들. 진단·테스트용. */
    fun current(): List<Pair<Rect, SerpVerdict>> = shown

    fun show(marks: List<Pair<Rect, SerpVerdict>>) {
        if (marks.isEmpty()) {
            clear()
            return
        }
        shown = marks
        render()
    }

    /** 스크롤한 만큼 통째로 밀어준다. 다시 판정하지 않고 자리만 따라간다. */
    fun offsetBy(dy: Int) {
        if (dy == 0 || shown.isEmpty()) return
        shown = shown.map { (rect, verdict) -> Rect(rect).apply { offset(0, dy) } to verdict }
        render()
    }

    fun clear() {
        shown = emptyList()
        removeOverlay()
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun attach(): FrameLayout {
        overlay?.let { return it }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCHABLE — 사용자의 터치를 그대로 통과시킨다. 제거 금지.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val root = FrameLayout(context)
        runCatching { windowManager.addView(root, params) }
        overlay = root
        return root
    }

    private fun render() {
        val root = attach()
        root.removeAllViews()

        for ((rect, verdict) in shown) {
            if (rect.width() <= 0 || rect.height() <= 0) continue
            root.addView(
                markView(verdict, rect.height()),
                FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                    leftMargin = rect.left
                    topMargin = rect.top
                }
            )
        }
    }

    private fun markView(verdict: SerpVerdict, heightPx: Int): View {
        val accent = verdict.grade.color
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(8).toFloat()
                val stroke = if (verdict.grade == RiskGrade.LOW) STROKE_LOW_DP else STROKE_HIGH_DP
                setStroke(dp(stroke), accent)
            }
        }

        // '안전'은 테두리로 충분하다. 열 개에 전부 글자가 붙으면 아무것도 안 읽힌다.
        if (verdict.grade != RiskGrade.LOW && heightPx >= dp(BADGE_MIN_HEIGHT_DP)) {
            container.addView(
                badge(verdict, accent),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    // **아래에 붙인다.** 실기기 확인(2026-08-14, SM-S937N, 구글
                    // "드라마 다시보기")에서 위에 붙이니 결과 제목을 그대로 덮었다.
                    // 어르신은 제목을 읽고 누를지 말지를 정하므로, 제목을 가리는
                    // 경고는 경고가 아니라 방해다. 아래를 덮으면 설명 마지막 줄이
                    // 가려지는데 그건 안 읽어도 되는 부분이다.
                    gravity = Gravity.BOTTOM or Gravity.START
                }
            )
        }
        return container
    }

    /**
     * 배지는 한 줄이다 — 등급 한 단어와 이유.
     *
     * 등급만 보여주면 왜 위험한지 모르고, 이유만 보여주면 얼마나 위험한지 모른다.
     * 두 줄로 만들면 카드를 그만큼 더 덮으므로 가로 한 줄로 눕혔고, 넘치는 이유는
     * 잘라낸다 — 어차피 다 읽지 않는다. 글자를 키운 것은 이 앱의 사용자가 작은
     * 글씨를 못 읽기 때문이다.
     */
    private fun badge(verdict: SerpVerdict, accent: Int): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadii = floatArrayOf(
                    dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }

            addView(TextView(context).apply {
                text = verdict.grade.label       // "위험" / "주의"
                setTextColor(Color.WHITE)
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(
                TextView(context).apply {
                    text = verdict.reason
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(8), 0, 0, 0)
                },
                // 남는 폭을 전부 차지하되 등급 글자를 밀어내지는 않는다
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
}

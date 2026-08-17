package com.senioradguard.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 닫기 버튼이 없는 광고를 **가려서** 없앤 것처럼 만든다.
 *
 * ## 왜 이게 필요한가 — X는 대부분 존재하지 않는다
 * "광고 모두 닫기"는 원래 광고 안의 작은 X를 대신 눌러 주는 기능이었다. 그런데
 * 실기기에서 접근성 트리를 떠 보니(네이트 뉴스, 크롬) 웹 배너 광고 컨테이너의
 * 자식은 이미지 하나뿐이고 **닫기 후보 노드가 0개**였다. X는 광고 iframe 안에서
 * 그려질 뿐 접근성 트리에 올라오지 않는다. 즉 누를 X가 애초에 없다.
 *
 * 그래서 못 찾으면 아무 일도 일어나지 않았고("닫기 버튼이 없어요" 토스트), 그나마
 * 폴백인 뒤로 가기는 광고가 아니라 사용자가 보던 페이지를 닫았다.
 *
 * 가리기는 그 둘 다를 피한다. 광고는 눈앞에서 사라지고, 읽던 페이지는 그대로다.
 *
 * ## 터치를 삼킨다 — 그게 요점이다
 * 테두리 오버레이는 광고 클릭을 방해하면 안 되므로 FLAG_NOT_TOUCHABLE이 필수다.
 * 이 창은 반대다. 사용자가 **직접 "광고 모두 닫기"를 눌러** 그 광고를 안 보겠다고
 * 밝힌 뒤에만 뜨므로, 광고를 잘못 눌러 끌려가는 것을 막는 것이 이 창의 목적이다.
 * 커버 자체를 탭하면 걷히므로 사용자는 언제든 되돌릴 수 있다.
 *
 * ## 스크롤하면 걷는다
 * 커버는 좌표를 추적하지 않는다. 화면이 구르면 광고도 함께 밀려 올라가므로 계속
 * 붙잡고 있을 이유가 없고, 잘못 덮었더라도 스크롤 한 번이면 풀린다.
 */
class AdCoverOverlay(private val context: Context) {

    private companion object {
        /** 커버가 이 시간을 넘겨 남아 있으면 스스로 걷는다 (안전핀) */
        const val MAX_COVER_MS = 30_000L
    }

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 광고 하나당 창 하나. **화면 전체를 덮는 창 하나로 만들면 안 된다** — 이 창은
     * 터치를 받아야 하는데(그게 존재 이유다), MATCH_PARENT로 띄우면 광고 밖의 빈
     * 영역까지 전부 터치를 삼킨다. 실기기에서 그렇게 만들었더니 화면 어디를 만져도
     * 반응이 없어 폰이 멈춘 것처럼 보였고, 스크롤조차 안 먹으니 스크롤로 걷히게 해
     * 둔 커버가 영영 남았다. 각 창을 광고 크기로 잘라 두면 나머지 화면은 평소대로
     * 동작한다.
     */
    private val panels = mutableListOf<FrameLayout>()

    val isShowing: Boolean get() = panels.isNotEmpty()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** 주어진 영역들을 덮는다. 이미 떠 있으면 새 목록으로 갈아 끼운다. */
    fun cover(regions: List<Rect>) {
        hide()
        for (r in regions) {
            if (r.width() <= 0 || r.height() <= 0) continue
            val view = FrameLayout(context).apply {
                addView(panel(), FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            val params = WindowManager.LayoutParams(
                r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 터치를 받는다(NOT_TOUCHABLE 없음) — 광고로 가는 탭을 막는 것이
                // 이 창의 존재 이유다. 키 입력은 아래로 흘려 뒤로 가기를 살려 둔다.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = r.left
                y = r.top
            }
            runCatching { windowManager.addView(view, params) }.onSuccess { panels.add(view) }
        }
        // 안전핀 — 어떤 경로로도 커버가 화면에 눌러붙지 않게 한다. 이 창은 터치를
        // 삼키므로 남아 있는 것 자체가 위험하고, 실제로 한 번 그렇게 눌러붙었다.
        handler.removeCallbacks(autoHide)
        if (panels.isNotEmpty()) handler.postDelayed(autoHide, MAX_COVER_MS)
    }

    private val autoHide = Runnable { hide() }

    /** 무엇이 왜 가려졌는지 한 줄로 알린다 — 빈 회색 칸만 남으면 그게 더 불안하다. */
    private fun panel(): TextView = TextView(context).apply {
        text = "광고를 가렸어요"
        textSize = 17f
        setTextColor(Color.parseColor("#DDDDDD"))
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.argb(252, 38, 40, 44))
            cornerRadius = dp(8).toFloat()
        }
        // 탭하면 전부 걷는다 — 잘못 가렸을 때 사용자가 스스로 되돌리는 길
        setOnClickListener { hide() }
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        for (p in panels) runCatching { windowManager.removeView(p) }
        panels.clear()
    }
}

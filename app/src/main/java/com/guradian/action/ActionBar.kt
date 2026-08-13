package com.guradian.action

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 상시 액션바 — 큰 버튼 하나. — task 2 (task 3이 여기 얹힌다)
 *
 * ## 구현 제약 (반드시 지킬 것)
 *  - `TYPE_ACCESSIBILITY_OVERLAY` + **서비스 컨텍스트**. `applicationContext`를 쓰면
 *    창 토큰이 없어 `addView`가 `BadTokenException`("token null is not valid")으로
 *    죽는다 — 액션바를 띄우는 순간마다 앱이 크래시한다.
 *  - **Compose가 아니라 순수 View.** 오버레이 창에는 `ViewTreeLifecycleOwner`와
 *    `SavedStateRegistryOwner`가 없어 Compose가 붙지 못한다.
 *  - 테두리 창(`FLAG_NOT_TOUCHABLE`)과 **별도 창**이어야 한다. 합치면 둘 다 터치를
 *    받거나(광고 클릭 방해 = 정책 위반) 둘 다 통과시킨다(버튼이 안 눌린다).
 *  - 터치 타깃 72dp, 글자 24sp 이상.
 *  - **광고 위에 겹치지 않는다.** 하단 바가 광고 Rect와 겹치면 상단으로 옮긴다.
 *    광고를 가리면 구글 정책 위반이다.
 */
class ActionBar(
    /** 반드시 서비스 컨텍스트. 위 주석 참고. */
    private val context: Context,
    private val onPrimaryClick: (PrimaryAction) -> Unit
) {

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val handler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var button: TextView? = null
    private var toast: TextView? = null
    private var handle: TextView? = null

    private var state = ActionBarState()
    private var collapsed = false
    private var atTop = false

    /** 지금 보여주고 있는 동작. 크로스페이드 중복 실행을 막는 데 쓴다. */
    private var rendered: PrimaryAction? = null

    private val hideToast = Runnable { toast?.visibility = View.GONE }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // ── 표시 · 해제 ─────────────────────────────────────────

    fun show() {
        if (root != null) return
        val view = build()
        windowManager.addView(view, params())
        root = view
        render(animate = false)
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        button = null
        toast = null
        handle = null
        rendered = null
    }

    // ── 상태 ───────────────────────────────────────────────

    fun setState(newState: ActionBarState) {
        if (state == newState) return
        state = newState
        render(animate = true)
    }

    fun setBusy(busy: Boolean) = setState(state.copy(busy = busy))

    /**
     * [광고 찾기] 결과를 알린다. **0건일 때도 반드시 부를 것** —
     * 무반응은 고장으로 읽히고, 어르신은 버튼이 안 먹었다고 생각해 계속 누른다.
     */
    fun reportFound(count: Int) {
        say(if (count > 0) "광고 ${count}개를 찾았어요" else "지금은 찾지 못했어요")
    }

    /** 안내 문구를 [TOAST_MS] 동안 액션바 위에 띄운다. */
    fun say(message: String) {
        val view = toast ?: return
        handler.removeCallbacks(hideToast)
        view.text = message
        view.visibility = View.VISIBLE
        handler.postDelayed(hideToast, TOAST_MS)
    }

    /**
     * 광고 영역과 겹치지 않게 위치를 정한다.
     *
     * 하단 [BAR_HEIGHT_DP]가 광고와 겹치면 상단으로 옮긴다. 광고를 가리면
     * 구글 정책 위반이라 이건 미관 문제가 아니다.
     */
    fun avoid(adRegions: List<Rect>) {
        val view = root ?: return
        val screenHeight = context.resources.displayMetrics.heightPixels
        val barTop = screenHeight - dp(BAR_HEIGHT_DP + BAR_MARGIN_DP * 2)
        val bottomBand = Rect(0, barTop, context.resources.displayMetrics.widthPixels, screenHeight)

        val shouldMoveUp = adRegions.any { Rect.intersects(it, bottomBand) }
        if (shouldMoveUp == atTop) return

        atTop = shouldMoveUp
        windowManager.updateViewLayout(view, params())
    }

    /**
     * ESCAPE 진입 연출 — 3배 확장 + 강한 진동. **화면은 막지 않는다.**
     * 끌려간 상황이라도 사용자가 스스로 조작할 자유를 뺏지 않는다.
     */
    fun expandForEscape() {
        collapsed = false
        val view = button ?: return
        view.animate().cancel()
        view.scaleY = 1f
        view.animate().scaleY(ESCAPE_SCALE).setDuration(FADE_MS).withEndAction {
            view.animate().scaleY(1f).setDuration(FADE_MS).start()
        }.start()
    }

    // ── 그리기 ─────────────────────────────────────────────

    private fun render(animate: Boolean) {
        val view = button ?: return
        val action = state.primary

        if (action == PrimaryAction.NONE) {
            root?.visibility = View.GONE
            rendered = action
            return
        }
        root?.visibility = View.VISIBLE

        if (action == rendered) return
        rendered = action

        val apply = {
            view.text = labelOf(action)
            view.background = pill(colorOf(action))
            view.isEnabled = action != PrimaryAction.BUSY
        }

        // 손 밑에서 버튼이 바뀌는 것을 눈치채게 한다. 즉시 갈아치우면 방금 누른
        // 버튼이 다른 기능이 된 것을 모른 채 한 번 더 누르게 된다.
        if (!animate) {
            apply()
            view.alpha = 1f
            return
        }
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction {
            apply()
            view.animate().alpha(1f).setDuration(FADE_MS / 2).start()
        }.start()
    }

    private fun labelOf(action: PrimaryAction) = when (action) {
        PrimaryAction.ESCAPE -> "돌아가기"
        PrimaryAction.CLOSE_AD -> "광고 닫기"
        PrimaryAction.FIND_AD -> "광고 찾기"
        PrimaryAction.BUSY -> "찾는 중…"
        PrimaryAction.NONE -> ""
    }

    private fun colorOf(action: PrimaryAction) = when (action) {
        PrimaryAction.ESCAPE -> Color.parseColor("#D32F2F")    // 빨강
        PrimaryAction.CLOSE_AD -> Color.parseColor("#F57C00")  // 주황
        PrimaryAction.FIND_AD -> Color.parseColor("#1976D2")   // 파랑
        PrimaryAction.BUSY -> Color.parseColor("#757575")      // 회색
        PrimaryAction.NONE -> Color.TRANSPARENT
    }

    // ── 뷰 구성 (프로그래매틱 — 오버레이라 XML 인플레이트를 피한다) ──

    private fun build(): FrameLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val toastView = TextView(context).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(Color.argb(230, 20, 20, 20))
            visibility = View.GONE
        }

        val primary = TextView(context).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // 터치 타깃 72dp — 손이 떨려도 놓치지 않는 크기
            minHeight = dp(BAR_HEIGHT_DP)
            setPadding(dp(32), dp(20), dp(32), dp(20))
            setOnClickListener {
                val action = state.primary
                if (action != PrimaryAction.NONE && action != PrimaryAction.BUSY) {
                    onPrimaryClick(action)
                }
            }
        }

        // 접기 손잡이 — 액션바가 늘 떠 있으므로 잠시 치울 방법이 있어야 한다
        val handleView = TextView(context).apply {
            text = "▾"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minHeight = dp(HANDLE_HEIGHT_DP)
            setPadding(dp(24), dp(4), dp(24), dp(4))
            background = pill(Color.argb(200, 60, 60, 60))
            setOnClickListener { toggleCollapsed() }
        }

        column.addView(
            toastView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )
        column.addView(
            primary,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        column.addView(
            handleView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        button = primary
        toast = toastView
        handle = handleView

        return FrameLayout(context).apply {
            setPadding(dp(BAR_MARGIN_DP), dp(BAR_MARGIN_DP), dp(BAR_MARGIN_DP), dp(BAR_MARGIN_DP))
            addView(column)
        }
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        button?.visibility = if (collapsed) View.GONE else View.VISIBLE
        toast?.visibility = View.GONE
        handle?.text = if (collapsed) "▴" else "▾"
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
    }

    /**
     * 터치를 **받는** 창이다. 테두리 창과 달리 FLAG_NOT_TOUCHABLE이 없다 —
     * 그래서 두 창을 합칠 수 없다.
     *
     * FLAG_NOT_FOCUSABLE은 유지한다. 포커스를 가져가면 대상 앱의 키보드 입력이
     * 끊긴다.
     */
    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = if (atTop) Gravity.TOP else Gravity.BOTTOM
    }

    private companion object {
        const val BAR_HEIGHT_DP = 72
        const val BAR_MARGIN_DP = 12
        const val HANDLE_HEIGHT_DP = 24

        /** 상태 전환 크로스페이드 */
        const val FADE_MS = 200L

        /** 안내 문구 표시 시간 */
        const val TOAST_MS = 3000L

        /** ESCAPE 진입 시 확장 배율 */
        const val ESCAPE_SCALE = 3f
    }
}

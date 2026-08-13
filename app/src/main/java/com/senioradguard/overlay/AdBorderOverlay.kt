package com.senioradguard.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 확정(공식 라벨) / AI 추정 — 테두리 모양과 배지 문구가 다르다. */
enum class AdMarkStyle { CONFIRMED, AI_GUESS }

/**
 * 광고 영역에 테두리와 배지를 그리는 비차단 오버레이.
 *
 * 팀원 AdDetectService의 showBorders/buildBorderView/setAdRegions/beep에서 이식.
 * FLAG_NOT_TOUCHABLE로 터치를 통과시켜 광고 클릭·구매·설치 선택을 일절 방해하지
 * 않는다 (구글 정책). 이 플래그는 절대 제거하면 안 된다.
 *
 * Layer 1(CONFIRMED)과 Layer 2(AI_GUESS)의 영역 목록을 따로 들고 한 창에 함께
 * 그린다. 한쪽이 갱신돼도 다른 쪽은 유지된다.
 *
 * ## 왜 뷰를 지웠다 다시 만들지 않는가
 * 드래그 중에는 [show]가 초당 대여섯 번 불린다. 예전 구현은 그때마다
 * `removeAllViews()` 후 FrameLayout·LinearLayout·TextView·GradientDrawable을
 * 전부 새로 만들었다. 좌표만 몇 픽셀 달라졌을 뿐인데 뷰 트리를 통째로 새로 짓는
 * 셈이라, 감지가 아무리 빨라도 그리는 쪽에서 지연과 GC가 생긴다.
 *
 * 지금은 같은 자리·같은 모양이면 **[View.setLayoutParams]로 위치만 갱신**하고
 * 뷰를 재사용한다. 모양이 달라졌을 때만 그 한 칸을 새로 만든다.
 *
 * 또 예전에는 갱신마다 [View.post]로 다음 프레임에 미뤘다. 창이 이미 배치된
 * 뒤에는 미룰 이유가 없고, 그 한 프레임이 그대로 추종 지연이 된다. 창 크기를
 * 아직 모르는 첫 그리기에서만 미룬다.
 */
class AdBorderOverlay(private val context: Context) {

    /**
     * "광고 모두 닫기"를 눌렀을 때 실행할 동작. 서비스가 넣어준다.
     * null이면 버튼을 띄우지 않는다.
     */
    var onCloseAllAds: (() -> Unit)? = null

    private companion object {
        /** 이보다 얇은 조각은 테두리를 그리지 않는다 */
        const val MIN_MARK_HEIGHT_DP = 40

        /** 배지("AD 광고")를 담을 수 있는 최소 높이 */
        const val BADGE_MIN_HEIGHT_DP = 80

        /**
         * 알림음·진동을 다시 울리기까지의 최소 간격.
         * 광고가 잠깐 사라졌다 돌아오는 경우에까지 다시 울리면 소리가 계속 난다.
         * 서비스 쪽 히스테리시스로 대부분 걸러지지만 마지막 안전장치로 둔다.
         */
        const val ALERT_MIN_GAP_MS = 3000L
    }

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val vibrator by lazy {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }
    private val handler = Handler(Looper.getMainLooper())

    private var overlay: FrameLayout? = null

    /**
     * 닫기 버튼은 **별도 창**이다. 테두리 창은 FLAG_NOT_TOUCHABLE이라 창 전체가
     * 터치를 통과시키므로 그 안에 버튼을 넣으면 눌리지 않는다. 구글 정책상 광고
     * 위를 덮는 테두리는 터치를 막으면 안 되고, 반대로 버튼은 터치를 받아야 하니
     * 두 창을 분리하는 것 말고는 방법이 없다.
     */
    private var closeBar: LinearLayout? = null

    private val shown = mutableMapOf<AdMarkStyle, List<Rect>>()

    /** 창이 배치되길 기다리는 중인가. 드래그 중에 post가 여러 개 쌓이는 것을 막는다. */
    private var layoutPending = false

    /** 마지막으로 알림음·진동을 울린 시각 */
    private var lastAlertAt = 0L

    /** 자식 뷰 하나의 생김새. 이게 같으면 뷰를 그대로 재사용한다. */
    private data class Spec(val style: AdMarkStyle, val withBadge: Boolean)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** 해당 스타일의 영역 목록을 교체한다. 다른 스타일의 표시는 그대로 둔다. */
    fun show(style: AdMarkStyle, regions: List<Rect>) {
        if (shown[style].orEmpty() == regions) return

        val wasEmpty = shown.values.all { it.isEmpty() }
        shown[style] = regions

        if (shown.values.all { it.isEmpty() }) {
            removeOverlay()
            removeCloseBar()
            return
        }

        if (wasEmpty && regions.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            if (now - lastAlertAt > ALERT_MIN_GAP_MS) {
                lastAlertAt = now
                if (prefs.getBoolean("sound", true)) beep()
                if (prefs.getBoolean("vibe", true)) vibrate()
            }
        }
        if (prefs.getBoolean("visual", true)) {
            render()
            showCloseBar()
        }
    }

    /**
     * 이미 그려둔 테두리를 [dy]만큼 **즉시** 옮긴다. 스캔을 기다리지 않는다.
     *
     * 트리 순회는 아무리 조여도 150~250ms가 걸리고 여기에 시스템 병합과 스로틀이
     * 얹히면 광고가 움직인 뒤 테두리가 따라오기까지 수백 ms다. 60fps가 16.7ms이니
     * 스캔 주기를 손보는 것으로는 절대 스무스해지지 않는다.
     *
     * 그래서 스크롤 이벤트가 오는 순간 **뷰의 위치만** 바꾼다. 노드를 읽지 않고
     * layoutParams만 건드리므로 프레임 단위로 붙는다. 진짜 좌표는 뒤따라오는
     * 스캔이 보정한다 — 즉 이 값은 "다음 스캔까지 버티는 추정치"다.
     */
    fun offsetBy(dy: Int) {
        if (dy == 0) return
        if (shown.values.all { it.isEmpty() }) return

        // 저장된 영역도 함께 민다. 안 그러면 다음 스캔 결과와 비교할 때 기준이
        // 어긋나, 실제로 같은 자리인데도 매번 다시 그리게 된다.
        for (style in shown.keys.toList()) {
            shown[style] = shown[style].orEmpty().map { Rect(it).apply { offset(0, dy) } }
        }

        val root = overlay ?: return
        for (i in 0 until root.childCount) {
            val view = root.getChildAt(i)
            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.topMargin += dy
            view.layoutParams = lp
        }
    }

    fun clear(style: AdMarkStyle) = show(style, emptyList())

    fun dismissAll() {
        shown.clear()
        removeOverlay()
        removeCloseBar()
    }

    // ──────────────────────────────────────────────────────────
    // 광고 모두 닫기 버튼 (터치를 받는 별도 창)
    // ──────────────────────────────────────────────────────────

    private fun showCloseBar() {
        val action = onCloseAllAds ?: return
        if (closeBar != null) return

        val button = TextView(context).apply {
            text = "✕  광고 모두 닫기"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 33, 33, 33))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(2), Color.parseColor("#FF5722"))
            }
            setOnClickListener { action() }
        }

        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(button)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // FLAG_NOT_TOUCHABLE을 주지 않는다 — 이 창은 눌려야 한다.
            // 대신 FLAG_NOT_FOCUSABLE로 키보드·뒤로가기는 아래 앱이 계속 받는다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(90)
        }

        runCatching { windowManager.addView(bar, params) }
            .onSuccess { closeBar = bar }
    }

    private fun removeCloseBar() {
        closeBar?.let { runCatching { windowManager.removeView(it) } }
        closeBar = null
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        layoutPending = false
    }

    private fun attach(): FrameLayout {
        overlay?.let { return it }
        val view = FrameLayout(context)
        windowManager.addView(
            view,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
        )
        overlay = view
        return view
    }

    private fun render() {
        val root = attach()
        // 창이 아직 배치되지 않았으면 크기·화면 좌표를 알 수 없다. 그때만 미룬다.
        if (root.width == 0 || root.height == 0) {
            if (!layoutPending) {
                layoutPending = true
                root.post {
                    layoutPending = false
                    if (overlay === root) draw(root)
                }
            }
            return
        }
        draw(root)
    }

    private fun draw(root: FrameLayout) {
        // 오버레이 창은 상태바 아래에서 시작할 수 있으므로 실제 창 위치만큼 좌표를 보정
        val loc = IntArray(2).also { root.getLocationOnScreen(it) }
        val win = Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
        val minHeight = dp(MIN_MARK_HEIGHT_DP)
        val badgeMinHeight = dp(BADGE_MIN_HEIGHT_DP)

        // 맵 순서에 기대지 않는다. 순서가 흔들리면 재사용 판정이 매번 어긋난다.
        var i = 0
        for (style in AdMarkStyle.values()) {
            for (r in shown[style].orEmpty()) {
                val c = Rect(r)
                // 창 밖·너무 얇은 조각은 생략
                if (!c.intersect(win) || c.height() < minHeight) continue

                val spec = Spec(style, c.height() >= badgeMinHeight)
                var view = root.getChildAt(i)
                if (view == null || view.tag != spec) {
                    if (view != null) root.removeViewAt(i)
                    view = buildBorderView(spec).apply { tag = spec }
                    root.addView(view, i, FrameLayout.LayoutParams(c.width(), c.height()))
                }

                val lp = view.layoutParams as FrameLayout.LayoutParams
                val left = c.left - win.left
                val top = c.top - win.top
                if (lp.width != c.width() || lp.height != c.height() ||
                    lp.leftMargin != left || lp.topMargin != top
                ) {
                    lp.width = c.width()
                    lp.height = c.height()
                    lp.setMargins(left, top, 0, 0)
                    view.layoutParams = lp   // requestLayout을 부른다
                }
                i++
            }
        }
        // 이번에 안 쓴 뷰는 남겨두면 사라진 광고의 테두리로 보인다
        if (i < root.childCount) root.removeViews(i, root.childCount - i)
    }

    private fun buildBorderView(spec: Spec): FrameLayout {
        val accent = when (spec.style) {
            AdMarkStyle.CONFIRMED -> Color.parseColor("#FF5722")   // 주황
            AdMarkStyle.AI_GUESS -> Color.parseColor("#FFC107")    // 노랑
        }
        val badgeLabel = when (spec.style) {
            AdMarkStyle.CONFIRMED -> "AD"
            AdMarkStyle.AI_GUESS -> "AI"
        }
        val badgeText = when (spec.style) {
            AdMarkStyle.CONFIRMED -> "광고"
            AdMarkStyle.AI_GUESS -> "광고 같아요"
        }

        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                when (spec.style) {
                    AdMarkStyle.CONFIRMED -> setStroke(dp(6), accent)
                    // AI 추정은 점선 — 확정과 시각적으로 구분해 오탐 시 오해를 줄인다
                    AdMarkStyle.AI_GUESS ->
                        setStroke(dp(6), accent, dp(12).toFloat(), dp(8).toFloat())
                }
            }
            // 영역이 배지를 담기에 너무 좁으면 테두리만 표시
            if (spec.withBadge) {
                addView(
                    buildBadge(accent, badgeLabel, badgeText),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(12), dp(12), 0, 0) }
                )
            }
        }
    }

    private fun buildBadge(accent: Int, label: String, text: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 20, 20, 20))
                cornerRadius = dp(14).toFloat()
            }
            addView(TextView(context).apply {
                this.text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(10).toFloat()
                }
            })
            addView(TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(10), 0, 0, 0)
            })
        }

    private fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        handler.postDelayed({ tone.release() }, 400)
    }

    private fun vibrate() {
        // minSdk 26이므로 VibrationEffect를 무조건 쓸 수 있다
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

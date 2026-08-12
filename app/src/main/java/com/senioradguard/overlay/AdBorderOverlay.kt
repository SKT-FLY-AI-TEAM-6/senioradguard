package com.senioradguard.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
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
 */
class AdBorderOverlay(private val context: Context) {

    /**
     * "광고 모두 닫기"를 눌렀을 때 실행할 동작. 서비스가 넣어준다.
     * null이면 버튼을 띄우지 않는다.
     */
    var onCloseAllAds: (() -> Unit)? = null

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val vibrator by lazy {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (wasEmpty && regions.isNotEmpty()) {
            if (prefs.getBoolean("sound", true)) beep()
            if (prefs.getBoolean("vibe", true)) vibrate()
        }
        if (prefs.getBoolean("visual", true)) {
            render()
            showCloseBar()
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
    }

    private fun render() {
        if (overlay == null) {
            overlay = FrameLayout(context)
            windowManager.addView(
                overlay,
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
        }
        overlay?.apply {
            // 오버레이 창은 상태바 아래에서 시작할 수 있으므로 실제 창 위치만큼 좌표를 보정
            post {
                val loc = IntArray(2).also { getLocationOnScreen(it) }
                val win = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
                removeAllViews()
                for ((style, regions) in shown) {
                    for (r in regions) {
                        val c = Rect(r)
                        // 창 밖·너무 얇은 조각은 생략
                        if (!c.intersect(win) || c.height() < dp(40)) continue
                        addView(
                            buildBorderView(style, c.height()),
                            FrameLayout.LayoutParams(c.width(), c.height()).apply {
                                setMargins(c.left - win.left, c.top - win.top, 0, 0)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun buildBorderView(style: AdMarkStyle, regionHeight: Int): FrameLayout {
        val accent = when (style) {
            AdMarkStyle.CONFIRMED -> Color.parseColor("#FF5722")   // 주황
            AdMarkStyle.AI_GUESS -> Color.parseColor("#FFC107")    // 노랑
        }
        val badgeLabel = when (style) {
            AdMarkStyle.CONFIRMED -> "AD"
            AdMarkStyle.AI_GUESS -> "AI"
        }
        val badgeText = when (style) {
            AdMarkStyle.CONFIRMED -> "광고"
            AdMarkStyle.AI_GUESS -> "광고 같아요"
        }

        val badge = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 20, 20, 20))
                cornerRadius = dp(14).toFloat()
            }
            addView(TextView(context).apply {
                text = badgeLabel
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(10).toFloat()
                }
            })
            addView(TextView(context).apply {
                text = badgeText
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(10), 0, 0, 0)
            })
        }

        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                when (style) {
                    AdMarkStyle.CONFIRMED -> setStroke(dp(6), accent)
                    // AI 추정은 점선 — 확정과 시각적으로 구분해 오탐 시 오해를 줄인다
                    AdMarkStyle.AI_GUESS ->
                        setStroke(dp(6), accent, dp(12).toFloat(), dp(8).toFloat())
                }
            }
            // 영역이 배지를 담기에 너무 좁으면 테두리만 표시
            if (regionHeight >= dp(80)) {
                addView(
                    badge,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(12), dp(12), 0, 0) }
                )
            }
        }
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

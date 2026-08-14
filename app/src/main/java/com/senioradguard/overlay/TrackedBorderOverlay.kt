package com.senioradguard.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.senioradguard.region.Anchor

/**
 * Layer 1(확정 광고) 테두리 — **앵커 추적**으로 광고에 fit하게 붙어 따라간다.
 *
 * ad_claude_fable(com.flyai.adalert)의 검증된 추적 엔진 이식. 원리:
 * 스크롤 중에는 광고가 "무엇인지"는 그대로고 "어디 있는지"만 바뀐다. 트리를
 * 다시 훑는 대신 이미 잡아둔 노드([Anchor])의 좌표만 8ms마다 다시 읽는다
 * (좌표 한 번 읽기 = 0.3~1.5ms IPC, 트리 순회와 비용이 두 자릿수 다르다).
 *
 * 표본 사이는 속도 예측(관성 항법)으로 메우고, 못 맞출 만큼 빠른 구간에서는
 * 흐려서 감춘다 — 어긋난 테두리는 곧바로 "기사를 광고로 표시한 것"으로 보이고,
 * 이 앱은 미탐보다 오탐이 훨씬 치명적이다.
 *
 * Choreographer는 여기서 안전하다: vsync 콜백은 보이는 창이 있는 프로세스에만
 * 오는데, 이 콜백은 오버레이 창이 떠 있는 동안에만 돌기 때문이다(창이 없으면
 * [leader]가 첫 프레임에 스스로 내린다).
 *
 * 색은 새 위험도 체계의 저위험(파랑 + "광고")을 따른다. 위험도별 색·배지는
 * 4e에서 이 클래스에 스타일 인자로 확장한다.
 */
class TrackedBorderOverlay(private val context: Context) {

    private companion object {
        const val TAG = "AdGuard"
        const val TRACK_INTERVAL_MS = 8L
        /** 이벤트가 끊긴 뒤에도 이 횟수만큼은 계속 따라간다 (관성 스크롤 구간, 8ms×100≈0.8초) */
        const val TRACK_TICKS = 100
        /** 표본 사이를 속도로 메울 수 있는 최대 시간 */
        const val MAX_LEAD_MS = 80L
        /** 예측으로 밀어줄 수 있는 최대 거리(px) */
        const val MAX_LEAD_PX = 40f
        /** 이보다 빠르면 흐리기 시작 / 완전히 감춤 (px/ms) */
        const val FADE_START_PX_PER_MS = 3.0f
        const val FADE_FULL_PX_PER_MS = 6.0f
        /** 좌표를 못 읽었을 때 감춘 채로 되돌아오기를 기다리는 시간 */
        const val TRACK_GRACE_MS = 400L
        /** 좌표가 이만큼 그대로면 트리 미갱신이 아니라 정말 멈춘 것으로 본다 */
        const val STALE_STOP_MS = 160L
        /** 흐림을 목표값까지 한 프레임에 옮기는 비율 */
        const val FADE_SMOOTH = 0.18f
        /** 이보다 빠른 한 표본은 스크롤이 아니라 화면 재배치다 */
        const val JUMP_PX_PER_MS = 15f
        /** 알림음·진동 최소 간격 */
        const val ALERT_MIN_GAP_MS = 3000L
        /** 배지 높이(dp). 테두리 밖 위쪽으로 올릴 거리 계산에 고정값이 필요하다 */
        const val BADGE_H = 34
    }

    /**
     * 영역별 표시 스타일 — 기획 3.1절의 위험도 색·어휘.
     * 색상만으로 구분하지 않는다는 원칙대로 배지 텍스트가 항상 함께 간다.
     */
    data class BorderStyle(val color: String, val badge: String) {
        companion object {
            /** 판정 전 기본: 파란 테두리 + 광고 */
            val AD = BorderStyle("#2179FD", "광고")
            /**
             * 저위험 확인 완료: 초록 테두리 + 광고 ✓ — 판정 전(파랑)과 구분한다.
             * 체크는 "안전 보증"이 아니라 "확인한 범위에서 신호 없음"이다.
             * 색만 바꾸지 않고 배지도 달리해 색각과 무관하게 읽히게 한다.
             */
            val SAFE = BorderStyle("#2E7D32", "광고 ✓")
            /** 중위험 연계 판정: 노란 테두리 + 주의 */
            val CAUTION = BorderStyle("#F9A825", "주의")
            /** 고위험 연계 판정: 빨간 테두리 + 위험 */
            val DANGER = BorderStyle("#E53935", "위험")
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 좌표 재측정은 IPC라 전용 스레드에서 돈다. 스캔과 같은 스레드에 두면 순회가 도는 동안 추적이 멈춘다 */
    private val trackThread = HandlerThread("ad-track").apply { start() }
    private val trackHandler = Handler(trackThread.looper)

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val vibrator by lazy {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** 앵커를 놓쳤을 때(광고가 사라졌을 가능성) 전체 스캔을 깨우기 위한 콜백 */
    var onAnchorLost: (() -> Unit)? = null

    private var overlay: FrameLayout? = null

    @Volatile private var shownRegions = emptyList<Rect>()
    @Volatile private var trackedAnchors = emptyList<Anchor?>()
    private var shownStyles = emptyList<BorderStyle>()
    @Volatile private var trackTicks = 0
    @Volatile private var tracking = false

    /** 영역별로 좌표가 마지막으로 실제로 바뀐 시각 (표본 시각이 아니다) */
    private var changedAt = LongArray(0)
    /** 영역별 세로 속도 (px/ms) */
    private var velocity = FloatArray(0)
    private var animating = false
    /** 좌표를 못 읽기 시작한 시각 (추적 스레드에서만) */
    private var lostAt = 0L
    private var bordersHidden = false
    private var lastAlertAt = 0L

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun regions(): List<Rect> = shownRegions

    /** 스캔 결과 반영 (메인 스레드) */
    fun set(
        regions: List<Rect>,
        anchors: List<Anchor?>,
        styles: List<BorderStyle> = emptyList()
    ) {
        trackedAnchors = if (anchors.size == regions.size) anchors else List(regions.size) { null }
        val newStyles = if (styles.size == regions.size) styles else List(regions.size) { BorderStyle.AD }
        // 스타일이 바뀌었으면(연계 판정이 새로 붙음) 좌표가 같아도 다시 그려야 한다
        val restyle = newStyles != shownStyles
        shownStyles = newStyles
        if (restyle && regions == shownRegions && regions.isNotEmpty()) {
            showBorders(regions)
        }
        setAdRegions(regions)
    }

    /** 이벤트가 올 때마다 불러 추적 루프를 깨워 둔다 */
    fun requestTracking() {
        if (shownRegions.isEmpty() || trackedAnchors.isEmpty()) return
        trackTicks = TRACK_TICKS
        if (!tracking) {
            tracking = true
            trackHandler.post(track)
        }
    }

    fun dismissAll() {
        trackedAnchors = emptyList()
        handler.post { setAdRegions(emptyList()) }
    }

    fun destroy() {
        trackHandler.removeCallbacksAndMessages(null)
        trackThread.quitSafely()
        setAdRegions(emptyList())
    }

    // ── 추적 루프 (전용 스레드) ──────────────────────────────────────────────

    private val track = object : Runnable {
        override fun run() {
            val started = SystemClock.uptimeMillis()
            val rects = try {
                measureAnchors()
            } catch (e: Throwable) {
                null
            }
            val done = SystemClock.uptimeMillis()
            if (rects != null) {
                lostAt = 0L
                // IPC 왕복의 한가운데를 표본 시각으로 본다 — 왕복이 느린 기기에서
                // 예측이 저절로 그만큼 앞당겨진다.
                val at = started + (done - started) / 2
                handler.post { moveBorders(rects, at) }
            } else {
                // 못 읽었다고 곧바로 지우면 전체 스캔이 다시 찾을 때까지 테두리가 비고,
                // 붙잡고 있으면 밑에서 올라온 기사에 씌워진다 → 감추고 잠깐 기다린다.
                if (lostAt == 0L) {
                    lostAt = done
                    handler.post { hideBorders() }
                }
                handler.post { onAnchorLost?.invoke() }
                if (done - lostAt > TRACK_GRACE_MS) {
                    lostAt = 0L
                    handler.post {
                        trackedAnchors = emptyList()
                        setAdRegions(emptyList())
                    }
                }
            }

            if (trackTicks-- > 0 && shownRegions.isNotEmpty()) {
                trackHandler.postDelayed(this, TRACK_INTERVAL_MS)
            } else {
                tracking = false
                handler.post { settleBorders() }
            }
        }
    }

    /**
     * 추적 중인 노드들의 현재 좌표. 하나라도 못 읽으면 표본을 통째로 버린다 —
     * **못 읽었다는 것 자체가 광고가 사라졌다는 신호다.**
     */
    private fun measureAnchors(): List<Rect>? {
        val anchors = trackedAnchors
        val prev = shownRegions
        if (anchors.isEmpty() || anchors.size != prev.size) return null

        val out = ArrayList<Rect>(anchors.size)
        for (i in anchors.indices) {
            val anchor = anchors[i]
            if (anchor == null) { out.add(prev[i]); continue }
            val measured = anchor.bounds() ?: return null
            // 합성 좌표(전체 화면 승격 등)는 노드를 따라 움직이면 안 된다.
            // 좌표를 읽은 것은 광고가 아직 살아 있는지 확인하기 위해서였다.
            out.add(if (anchor.follow) measured else prev[i])
        }
        return out
    }

    // ── 테두리 이동·예측 (메인 스레드) ──────────────────────────────────────

    /** 테두리를 새로 만들지 않고 자리만 옮긴다. 개수가 달라졌으면 전체 스캔에 맡긴다 */
    private fun moveBorders(regions: List<Rect>, sampledAt: Long) {
        if (regions.size != shownRegions.size || overlay == null) return
        val prev = shownRegions

        if (velocity.size != regions.size) {
            velocity = FloatArray(regions.size)
            changedAt = LongArray(regions.size) { sampledAt }
        }
        for (i in regions.indices) {
            val moved = regions[i].top - prev[i].top
            if (moved == 0) {
                // 화면이 멎은 것과 트리가 아직 갱신 안 된 것은 다르다. 갱신이 올 때까지는
                // 마지막 속도로 계속 밀어준다 — 그게 예측을 넣은 이유다.
                if (sampledAt - changedAt[i] > STALE_STOP_MS) velocity[i] = 0f
                continue
            }
            // 표본 간격이 아니라 **좌표가 바뀐 간격**으로 나눈다. 이게 핵심이다.
            val dt = (sampledAt - changedAt[i]).toFloat()
            changedAt[i] = sampledAt
            if (dt < 1f || dt > STALE_STOP_MS) {
                velocity[i] = 0f
                continue
            }
            val v = moved / dt
            if (kotlin.math.abs(v) >= JUMP_PX_PER_MS) {
                // 스크롤이 아니라 재배치(지연 로드로 내용이 밀림). 위치는 따라가되 속도로 세지 않는다
                velocity[i] = 0f
                continue
            }
            velocity[i] = when {
                v * velocity[i] < 0f -> 0f          // 방향 반전 → 예측이 틀렸다. 실측만 따른다
                velocity[i] == 0f -> v              // 첫 속도는 그대로 (필터를 먹이면 시작이 굼뜨다)
                else -> velocity[i] * 0.35f + v * 0.65f   // 낮은 통과 필터
            }
        }

        shownRegions = regions
        if (regions != prev || bordersHidden) layoutBorders(regions)
        startLeading()
    }

    /** 좌표를 놓친 동안 테두리를 지우지 않고 감춘다 (제자리에 남으면 기사에 씌워진다) */
    private fun hideBorders() {
        val group = overlay ?: return
        bordersHidden = true
        for (i in 0 until group.childCount) group.getChildAt(i)?.visibility = View.GONE
    }

    /** 화면이 멎었다. 예측을 걷고 실측 좌표 그대로 앉힌다 (흐림은 leader가 서서히 되돌린다) */
    private fun settleBorders() {
        velocity.fill(0f)
        val group = overlay ?: return
        for (i in 0 until group.childCount) group.getChildAt(i)?.translationY = 0f
        startLeading()
    }

    /** 표본 사이를 매 화면 갱신마다 속도×경과시간으로 메운다 (translation만 바꿔 프레임마다 싸다) */
    private fun startLeading() {
        if (animating) return
        animating = true
        Choreographer.getInstance().postFrameCallback(leader)
    }

    private val leader = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val group = overlay
            if (group == null) {
                animating = false
                return
            }
            val now = SystemClock.uptimeMillis()
            var moving = false
            for (i in 0 until group.childCount) {
                val v = velocity.getOrElse(i) { 0f }
                if (v != 0f) moving = true
                val view = group.getChildAt(i) ?: continue
                // 앞질러 그릴 시간은 그 영역의 좌표가 마지막으로 바뀐 뒤 흐른 시간이다
                val lead = (now - changedAt.getOrElse(i) { now }).coerceIn(0L, MAX_LEAD_MS)
                view.translationY = (v * lead).coerceIn(-MAX_LEAD_PX, MAX_LEAD_PX)
                // 너무 빨라 못 맞추는 구간에서는 흐려서 감춘다
                val speed = kotlin.math.abs(v)
                val a = when {
                    speed <= FADE_START_PX_PER_MS -> 1f
                    speed >= FADE_FULL_PX_PER_MS -> 0f
                    else -> 1f - (speed - FADE_START_PX_PER_MS) /
                        (FADE_FULL_PX_PER_MS - FADE_START_PX_PER_MS)
                }
                val next = view.alpha + (a - view.alpha) * FADE_SMOOTH
                if (kotlin.math.abs(a - next) < 0.01f) {
                    view.alpha = a
                } else {
                    view.alpha = next
                    moving = true
                }
            }
            if (tracking || moving) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                animating = false
            }
        }
    }

    // ── 그리기 ──────────────────────────────────────────────────────────────

    private fun setAdRegions(regions: List<Rect>) {
        if (regions.isNotEmpty()) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val now = SystemClock.uptimeMillis()
            if (shownRegions.isEmpty() && now - lastAlertAt > ALERT_MIN_GAP_MS) {
                lastAlertAt = now
                if (prefs.getBoolean("sound", true)) beep()
                if (prefs.getBoolean("vibe", true)) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
            }
            // 좌표가 같아도 감춰 둔 상태였으면 다시 그려야 한다 (전체 화면 광고 연속)
            if (prefs.getBoolean("visual", true) && (regions != shownRegions || bordersHidden)) {
                showBorders(regions)
            }
        } else if (shownRegions.isNotEmpty()) {
            overlay?.let { runCatching { windowManager.removeView(it) } }
            overlay = null
        }
        shownRegions = regions
    }

    private fun showBorders(regions: List<Rect>) {
        val group = overlay ?: FrameLayout(context).also {
            overlay = it
            // 배지가 테두리 위쪽 밖으로 나간다. 여기서 잘라내면 배지가 통째로 사라진다
            it.clipChildren = false
            windowManager.addView(it, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // 절대 제거 금지 — 광고 클릭·구매를 방해하면 구글 정책 위반이다
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ))
        }
        // 개수가 같으면 뷰를 재사용하고 자리만 바꾼다 (다 지웠다 그리면 그게 깜빡임이다)
        while (group.childCount > regions.size) group.removeViewAt(group.childCount - 1)
        while (group.childCount < regions.size) {
            val style = shownStyles.getOrElse(group.childCount) { BorderStyle.AD }
            group.addView(buildBorderView(style), FrameLayout.LayoutParams(0, 0))
        }
        layoutBorders(regions)
    }

    private fun layoutBorders(regions: List<Rect>) {
        val group = overlay ?: return
        if (group.width == 0 || group.height == 0) {
            group.post { layoutBorders(regions) }
            return
        }
        bordersHidden = false
        val loc = IntArray(2).also { group.getLocationOnScreen(it) }
        val win = Rect(loc[0], loc[1], loc[0] + group.width, loc[1] + group.height)
        for (i in regions.indices) {
            var v = group.getChildAt(i) as? FrameLayout ?: continue
            // 연계 판정이 붙어 스타일이 바뀐 칸은 그 칸만 새로 만든다
            val style = shownStyles.getOrElse(i) { BorderStyle.AD }
            if (v.tag != style) {
                group.removeViewAt(i)
                v = buildBorderView(style)
                group.addView(v, i, FrameLayout.LayoutParams(0, 0))
            }
            val c = Rect(regions[i])
            // 창 밖 조각은 감춘다. 하한을 낮게 둬야 320x50 인라인 배너를 놓치지 않는다
            if (!c.intersect(win) || c.height() < dp(20)) {
                v.visibility = View.GONE
                continue
            }
            v.visibility = View.VISIBLE
            // 배지는 광고 오른쪽 위 **바깥**에 얹는다 — 광고 안을 가리지 않기 위해서다.
            // 광고가 창 맨 위에 붙어 있으면 올릴 자리가 없어 그때만 안으로 들인다.
            val badge = v.getChildAt(0)
            if (badge != null) {
                badge.visibility = View.VISIBLE
                val room = c.top - win.top >= dp(BADGE_H)
                (badge.layoutParams as FrameLayout.LayoutParams).let {
                    // 3dp 겹쳐 얹어야 테두리 선과 배지 사이에 틈이 안 보인다
                    it.topMargin = if (room) -dp(BADGE_H - 3) else 0
                    badge.layoutParams = it
                }
            }
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.width = c.width()
            lp.height = c.height()
            lp.setMargins(c.left - win.left, c.top - win.top, 0, 0)
            v.layoutParams = lp
        }
    }

    private fun buildBorderView(style: BorderStyle): FrameLayout {
        // 배지는 한 낱말짜리 알약 하나 — 위험도 어휘(광고/주의/위험)와 같은 말이다.
        val badge = TextView(context).apply {
            text = style.badge
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(style.color))
                // 아래 두 귀퉁이는 각지게 — 테두리 윗변에 얹혀 앉은 한 덩어리로 읽힌다
                cornerRadii = floatArrayOf(
                    dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }
        return FrameLayout(context).apply {
            tag = style
            background = GradientDrawable().apply {
                setStroke(dp(6), Color.parseColor(style.color))
                setColor(Color.TRANSPARENT)
            }
            clipChildren = false
            clipToPadding = false
            addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(BADGE_H)
            ).apply { gravity = Gravity.TOP or Gravity.END })
        }
    }

    private fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        handler.postDelayed({ tone.release() }, 400)
    }
}

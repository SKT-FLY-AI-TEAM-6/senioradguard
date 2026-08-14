package com.senioradguard.guard


/**
 * "방금 광고를 눌렀는가"를 기억한다.
 *
 * ## 왜 필요한가
 * 쇼핑몰로 넘어간 것만 보고 경고하면, 어르신이 **스스로 쿠팡을 켠 경우에도**
 * "광고를 통해 이동했습니다"가 뜬다. 멀쩡한 행동에 경고가 붙으면 앱을 못 믿게
 * 되고, 그때부터는 진짜 경고도 무시하게 된다.
 *
 * 그래서 광고 노드를 누른 직후에만 발동하도록 문을 하나 더 둔다.
 *
 * ## 클릭 "좌표"가 아니라 눌린 노드의 영역을 본다
 * 접근성 이벤트는 손가락이 닿은 좌표를 주지 않는다. 주는 것은 눌린 노드이고,
 * 그 노드의 화면 영역은 알 수 있다. 광고 영역과 겹치는 노드가 눌렸다면 광고를
 * 누른 것으로 본다.
 *
 * ## 시간 제한
 * 플래그는 [TTL_MS] 동안만 유효하고 한 번 쓰면 사라진다. 없으면 아침에 누른
 * 광고 때문에 저녁에 쿠팡을 켰을 때 경고가 뜬다.
 */
class AdClickTracker(private val now: () -> Long = System::currentTimeMillis) {

    /**
     * 화면 영역. `android.graphics.Rect`를 쓰지 않는다 — JVM 단위 테스트에서는
     * 그 클래스의 좌표 필드가 전부 0으로 나와(스텁 android.jar) 겹침 판정을
     * 검증할 수 없다. 안드로이드에 기대지 않는 값으로 두면 테스트가 그대로 돈다.
     */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    companion object {
        /** 광고를 누르고 화면이 바뀌기까지 허용할 시간. */
        const val TTL_MS = 3_000L

        /**
         * 광고 영역을 기억해 둘 시간. 스캔은 수백 ms마다 갱신되지만, 화면이
         * 바뀌는 순간에는 이미 트리가 사라져 다시 읽을 수 없다.
         */
        const val REGION_TTL_MS = 5_000L
    }

    private var regions: List<Bounds> = emptyList()
    private var regionsAt = 0L
    private var pendingUntil = 0L

    /** 스캔이 광고를 찾을 때마다 부른다. */
    fun recordAdRegions(found: List<Bounds>) {
        if (found.isEmpty()) return
        regions = found
        regionsAt = now()
    }

    /**
     * 노드가 눌렸을 때 부른다.
     * @param clicked 눌린 노드의 화면 영역. 모르면 null.
     * @return 광고를 누른 것으로 판단했으면 true
     */
    fun onClick(clicked: Bounds?): Boolean {
        if (clicked == null) return false
        if (now() - regionsAt > REGION_TTL_MS) return false
        if (regions.none { overlaps(it, clicked) }) return false

        pendingUntil = now() + TTL_MS
        return true
    }

    /**
     * 두 사각형이 겹치는가.
     *
     * `Rect.intersects`를 쓰지 않는다. android.graphics의 정적 메서드라 JVM
     * 테스트에서 "not mocked"로 죽는다.
     */
    private fun overlaps(a: Bounds, b: Bounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /**
     * 광고를 눌러서 여기까지 온 것인가. **한 번 쓰면 사라진다** — 같은 클릭으로
     * 두 번 경고하면 화면을 옮길 때마다 다시 뜬다.
     */
    fun consumePendingClick(): Boolean {
        val valid = now() < pendingUntil
        pendingUntil = 0
        return valid
    }

    /** 지금 유효한 광고 클릭이 있는가 (소모하지 않는다). 진단용. */
    fun hasPendingClick(): Boolean = now() < pendingUntil
}

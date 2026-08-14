package com.senioradguard.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "광고를 눌러서 왔는가" 판정.
 *
 * 이게 없으면 어르신이 스스로 쿠팡을 켠 경우에도 경고가 뜬다. 멀쩡한 행동에
 * 경고가 붙으면 앱을 못 믿게 되고, 그때부터 진짜 경고도 무시하게 된다.
 */
class AdClickTrackerTest {

    private var clock = 10_000L
    private fun tracker() = AdClickTracker { clock }

    private val adRegion = AdClickTracker.Bounds(0, 100, 1080, 400)

    @Test
    fun `광고 위를 누르면 플래그가 선다`() {
        val t = tracker()
        t.recordAdRegions(listOf(adRegion))

        assertTrue(t.onClick(AdClickTracker.Bounds(200, 150, 300, 250)))
        assertTrue(t.hasPendingClick())
    }

    @Test
    fun `광고 밖을 누르면 플래그가 서지 않는다`() {
        val t = tracker()
        t.recordAdRegions(listOf(adRegion))

        assertFalse(t.onClick(AdClickTracker.Bounds(0, 800, 200, 900)))
        assertFalse(t.hasPendingClick())
    }

    // 직접 앱을 켠 경우 — 클릭 자체가 없다.
    @Test
    fun `클릭이 없었으면 경고하지 않는다`() {
        val t = tracker()
        t.recordAdRegions(listOf(adRegion))

        assertFalse(t.consumePendingClick())
    }

    @Test
    fun `눌린 노드를 모르면 판단하지 않는다`() {
        val t = tracker()
        t.recordAdRegions(listOf(adRegion))

        assertFalse(t.onClick(null))
    }

    // 아침에 누른 광고 때문에 저녁에 쿠팡을 켰을 때 경고가 뜨면 안 된다.
    @Test
    fun `3초가 지나면 플래그가 만료된다`() {
        val t = tracker()
        t.recordAdRegions(listOf(adRegion))
        t.onClick(AdClickTracker.Bounds(200, 150, 300, 250))

        clock += AdClickTracker.TTL_MS + 1
        assertFalse(t.consumePendingClick())
    }

    // 같은 클릭으로 두 번 경고하면 화면을 옮길 때마다 다시 뜬다.
    @Test
    fun `한 번 쓰면 사라진다`() {
        val t = tracker()
        t.recordAdRegions(listOf(adRegion))
        t.onClick(AdClickTracker.Bounds(200, 150, 300, 250))

        assertTrue(t.consumePendingClick())
        assertFalse("두 번째는 없어야 한다", t.consumePendingClick())
    }

    // 광고 영역은 스캔할 때 기록된다. 한참 전 좌표로 판단하면
    // 이미 사라진 광고 자리를 눌러도 광고 클릭이 된다.
    @Test
    fun `오래된 광고 좌표로는 판단하지 않는다`() {
        val t = tracker()
        t.recordAdRegions(listOf(adRegion))

        clock += AdClickTracker.REGION_TTL_MS + 1
        assertFalse(t.onClick(AdClickTracker.Bounds(200, 150, 300, 250)))
    }

    @Test
    fun `광고가 없으면 기존 기록을 지우지 않는다`() {
        // 스캔이 잠깐 0건을 내도(예산 초과 등) 직전 좌표는 유효하다.
        val t = tracker()
        t.recordAdRegions(listOf(adRegion))
        t.recordAdRegions(emptyList())

        assertTrue(t.onClick(AdClickTracker.Bounds(200, 150, 300, 250)))
    }
}

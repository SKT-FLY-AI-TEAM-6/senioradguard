package com.senioradguard.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 보호자 기록의 중복 제거.
 *
 * 여기서 막지 못하면 한 페이지를 훑는 동안 같은 광고가 스크롤 횟수만큼 원격에
 * 쌓인다. 실기기 측정으로 한 페이지에 스캔이 13회까지 나온 적이 있다.
 */
class SightingLogTest {

    @Test
    fun `처음 본 출처는 알린다`() {
        val log = SightingLog()
        assertTrue(log.shouldReport("hankyung.com", 1))
    }

    @Test
    fun `같은 출처와 레이어는 한 번만 알린다`() {
        val log = SightingLog()
        log.shouldReport("hankyung.com", 1)

        repeat(20) {
            assertFalse("스크롤할 때마다 다시 알리면 안 된다", log.shouldReport("hankyung.com", 1))
        }
    }

    @Test
    fun `같은 출처라도 레이어가 다르면 따로 알린다`() {
        val log = SightingLog()
        log.shouldReport("hankyung.com", 1)

        // Layer 1이 라벨 광고를, Layer 2가 라벨 없는 광고를 각각 잡은 상황.
        // 서로 다른 광고이므로 둘 다 알려야 한다.
        assertTrue(log.shouldReport("hankyung.com", 2))
    }

    @Test
    fun `출처가 다르면 각각 알린다`() {
        val log = SightingLog()
        log.shouldReport("hankyung.com", 1)

        assertTrue(log.shouldReport("gmarket.co.kr", 1))
        assertTrue(log.shouldReport("com.android.chrome", 1))
    }

    @Test
    fun `용량을 넘기면 오래된 것부터 버린다`() {
        val log = SightingLog(capacity = 3)
        log.shouldReport("a.com", 1)
        log.shouldReport("b.com", 1)
        log.shouldReport("c.com", 1)

        log.shouldReport("d.com", 1)   // a.com이 밀려난다

        assertTrue("밀려난 출처는 다시 알릴 수 있어야 한다", log.shouldReport("a.com", 1))
        assertFalse("남아 있는 출처는 여전히 막혀야 한다", log.shouldReport("c.com", 1))
    }

    @Test
    fun `최근에 다시 본 출처는 밀려나지 않는다`() {
        val log = SightingLog(capacity = 3)
        log.shouldReport("a.com", 1)
        log.shouldReport("b.com", 1)
        log.shouldReport("c.com", 1)

        // a.com을 다시 조회 — 접근 순서 갱신으로 가장 오래된 것은 b.com이 된다.
        // 이게 없으면 자주 보는 사이트가 먼저 밀려나 중복 기록이 난다.
        log.shouldReport("a.com", 1)
        log.shouldReport("d.com", 1)

        assertFalse("방금 조회한 a.com이 밀려나면 안 된다", log.shouldReport("a.com", 1))
        assertTrue("가장 오래 안 본 b.com이 밀려나야 한다", log.shouldReport("b.com", 1))
    }
}

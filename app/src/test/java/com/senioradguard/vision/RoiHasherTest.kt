package com.senioradguard.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 지문이 이 레이어의 캐시 키 전부다. 여기가 흔들리면 같은 배너를 스크롤할 때마다
 * 다시 판별하거나(비용 폭발), 서로 다른 배너가 한 판정을 공유한다(오탐).
 */
class RoiHasherTest {

    /** width×height 회색조를 만든다. [value]는 (x, y) → 밝기. */
    private fun gray(
        width: Int = RoiHasher.WIDTH,
        height: Int = RoiHasher.HEIGHT,
        value: (Int, Int) -> Int
    ) = IntArray(width * height) { i -> value(i % width, i / width) }

    @Test
    fun `왼쪽이 밝으면 비트가 선다`() {
        // 가로로 계속 어두워지는 그림 → 모든 이웃 비교가 '왼쪽이 크다'
        val hash = RoiHasher.dHash(gray { x, _ -> 255 - x * 20 })
        assertEquals("64비트가 전부 1", -1L, hash)
    }

    @Test
    fun `오른쪽이 밝으면 비트가 서지 않는다`() {
        val hash = RoiHasher.dHash(gray { x, _ -> x * 20 })
        assertEquals(0L, hash)
    }

    // 광고가 애니메이션으로 살짝 밝아졌다 어두워지는 일은 흔하다.
    // dHash는 이웃끼리의 대소만 보므로 전체 밝기 변화에 흔들리면 안 된다
    @Test
    fun `전체 밝기가 달라져도 같은 지문이다`() {
        val dark = RoiHasher.dHash(gray { x, y -> (x * 7 + y * 13) % 100 })
        val bright = RoiHasher.dHash(gray { x, y -> (x * 7 + y * 13) % 100 + 120 })

        assertEquals(dark, bright)
    }

    @Test
    fun `무늬가 다르면 지문도 다르다`() {
        val checker = RoiHasher.dHash(gray { x, y -> if ((x + y) % 2 == 0) 200 else 50 })
        val gradient = RoiHasher.dHash(gray { x, _ -> x * 25 })

        assertNotEquals(checker, gradient)
        // 체커보드는 한쪽으로 쏠리지 않는다 — 전부 0이나 전부 1이면 무늬를 못 본 것이다
        assertNotEquals(0L, checker)
        assertNotEquals(-1L, checker)
    }

    @Test
    fun `해밍 거리로 닮음을 잰다`() {
        assertEquals(0, RoiHasher.distance(0b1011L, 0b1011L))
        assertEquals(2, RoiHasher.distance(0b1011L, 0b1110L))

        assertTrue(RoiHasher.similar(0L, 0b111L))                  // 3비트 차이
        assertFalse(RoiHasher.similar(0L, 0b1111_1111L))           // 8비트 차이
    }

    @Test
    fun `크기가 맞지 않으면 0을 돌려준다`() {
        assertEquals(0L, RoiHasher.dHash(IntArray(10)))
        assertEquals(0L, RoiHasher.dHash(IntArray(0)))
    }

    // 같은 그림이라도 어느 앱·사이트에서 나왔는지에 따라 판단이 달라진다
    @Test
    fun `키에 출처가 들어간다`() {
        assertNotEquals(
            RoiHasher.key("yna.co.kr", 123L),
            RoiHasher.key("com.google.android.youtube", 123L)
        )
        assertTrue(RoiHasher.key("yna.co.kr", 255L).endsWith("|ff"))
    }

    // 최상위 비트가 선 지문을 부호 있는 정수로 찍으면 '-'가 들어간 키가 나온다
    @Test
    fun `음수 지문도 키가 깨지지 않는다`() {
        val key = RoiHasher.key("a", -1L)
        assertEquals("a|ffffffffffffffff", key)
    }
}

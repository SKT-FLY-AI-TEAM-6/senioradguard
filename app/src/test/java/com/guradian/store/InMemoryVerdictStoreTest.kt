package com.guradian.store

import com.guradian.agent.Verdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryVerdictStoreTest {

    private var clock = 1_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun store(maxEntries: Int = 500) =
        InMemoryVerdictStore(maxEntries, 30 * day) { clock }

    private fun verdict(isAd: Boolean = true) = Verdict(isAd, 0.9f, "x")

    @Test
    fun `넣은 판정을 그대로 돌려준다`() = runTest {
        val s = store()
        s.put("a.com|hash", verdict())
        assertEquals(verdict(), s.get("a.com|hash"))
    }

    @Test
    fun `없는 키는 null`() = runTest {
        assertNull(store().get("없음"))
    }

    // 절감의 대부분이 부정 판정 캐시에서 나온다 — 저장되지 않으면 매번 다시 부른다
    @Test
    fun `광고가 아니라는 판정도 저장한다`() = runTest {
        val s = store()
        s.put("k", verdict(isAd = false))
        assertEquals(false, s.get("k")?.isAd)
    }

    // 광고는 교체된다. 오래된 판정을 믿으면 이미 바뀐 자리에 테두리를 그린다.
    @Test
    fun `유효기간이 지나면 없는 것으로 본다`() = runTest {
        val s = store()
        s.put("k", verdict())
        clock += 31 * day
        assertNull(s.get("k"))
    }

    @Test
    fun `유효기간 안이면 살아 있다`() = runTest {
        val s = store()
        s.put("k", verdict())
        clock += 29 * day
        assertNotNull(s.get("k"))
    }

    @Test
    fun `만료된 항목은 조회하면서 버린다`() = runTest {
        val s = store()
        s.put("k", verdict())
        clock += 31 * day
        s.get("k")
        assertEquals(0, s.size())
    }

    @Test
    fun `용량을 넘으면 가장 오래 안 쓴 것부터 버린다`() = runTest {
        val s = store(maxEntries = 3)
        s.put("a", verdict()); s.put("b", verdict()); s.put("c", verdict())
        s.put("d", verdict())

        assertEquals(3, s.size())
        assertNull(s.get("a"))
        assertNotNull(s.get("d"))
    }

    // LRU다 — 최근에 "읽은" 것도 살아남아야 한다 (넣은 순서가 아니라 쓴 순서)
    @Test
    fun `최근에 조회한 항목은 축출되지 않는다`() = runTest {
        val s = store(maxEntries = 3)
        s.put("a", verdict()); s.put("b", verdict()); s.put("c", verdict())
        s.get("a")               // a를 최근 사용으로 끌어올린다
        s.put("d", verdict())    // 이제 b가 가장 오래됐다

        assertNotNull(s.get("a"))
        assertNull(s.get("b"))
    }

    @Test
    fun `같은 키를 다시 넣으면 덮어쓴다`() = runTest {
        val s = store()
        s.put("k", verdict(isAd = true))
        s.put("k", verdict(isAd = false))

        assertEquals(1, s.size())
        assertEquals(false, s.get("k")?.isAd)
    }

    // 덮어쓸 때 저장 시각도 갱신돼야 한다
    @Test
    fun `덮어쓰면 유효기간이 다시 시작된다`() = runTest {
        val s = store()
        s.put("k", verdict())
        clock += 29 * day
        s.put("k", verdict())
        clock += 29 * day

        assertNotNull(s.get("k"))
    }
}

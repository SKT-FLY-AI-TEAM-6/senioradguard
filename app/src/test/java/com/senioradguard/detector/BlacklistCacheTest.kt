package com.senioradguard.detector

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BlacklistCacheTest {

    private val loadCount = AtomicInteger(0)

    private suspend fun loader(): Set<String> {
        loadCount.incrementAndGet()
        return setOf("doubleclick.net")
    }

    @Before
    fun reset() {
        BlacklistCache.invalidate()
        loadCount.set(0)
    }

    @Test
    fun `첫 조회는 로더를 호출한다`() = runTest {
        val result = BlacklistCache.domains(::loader)
        assertEquals(setOf("doubleclick.net"), result)
        assertEquals(1, loadCount.get())
    }

    @Test
    fun `두 번째 조회는 캐시를 쓴다`() = runTest {
        BlacklistCache.domains(::loader)
        BlacklistCache.domains(::loader)
        assertEquals(1, loadCount.get())
    }

    @Test
    fun `invalidate 후에는 다시 로드한다`() = runTest {
        BlacklistCache.domains(::loader)
        BlacklistCache.invalidate()
        BlacklistCache.domains(::loader)
        assertEquals(2, loadCount.get())
    }
}

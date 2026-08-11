package com.senioradguard.detector.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdVerdictDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AdVerdictDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.adVerdictDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun 저장한_판정을_다시_읽는다() = runTest {
        val now = 1_000_000L
        dao.upsert(AdVerdict("naver.com|abc", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = now))

        val found = dao.find("naver.com|abc", notBefore = now - 1)

        assertNotNull(found)
        assertEquals(true, found!!.isAd)
        assertEquals(0.9f, found.confidence, 0.001f)
        assertEquals("LLM", found.source)
    }

    @Test
    fun 부정_판정도_저장된다() = runTest {
        val now = 1_000_000L
        dao.upsert(AdVerdict("naver.com|def", isAd = false, confidence = 0.1f, source = "LLM", updatedAt = now))

        val found = dao.find("naver.com|def", notBefore = now - 1)

        assertNotNull(found)
        assertEquals(false, found!!.isAd)
    }

    @Test
    fun 같은_키로_저장하면_덮어쓴다() = runTest {
        dao.upsert(AdVerdict("k", isAd = false, confidence = 0.1f, source = "LLM", updatedAt = 100L))
        dao.upsert(AdVerdict("k", isAd = true, confidence = 0.8f, source = "VISION_LLM", updatedAt = 200L))

        val found = dao.find("k", notBefore = 0L)

        assertEquals(true, found!!.isAd)
        assertEquals("VISION_LLM", found.source)
    }

    @Test
    fun TTL이_지난_판정은_조회되지_않는다() = runTest {
        dao.upsert(AdVerdict("old", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = 100L))

        assertNull(dao.find("old", notBefore = 500L))
    }

    @Test
    fun 없는_키는_null() = runTest {
        assertNull(dao.find("missing", notBefore = 0L))
    }

    @Test
    fun deleteExpired는_만료된_행만_지운다() = runTest {
        dao.upsert(AdVerdict("old", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = 100L))
        dao.upsert(AdVerdict("new", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = 900L))

        val deleted = dao.deleteExpired(notBefore = 500L)

        assertEquals(1, deleted)
        assertNull(dao.find("old", notBefore = 0L))
        assertNotNull(dao.find("new", notBefore = 0L))
    }

    @Test
    fun 블랙리스트_DAO도_계속_동작한다() = runTest {
        db.blacklistDao().insertAll(listOf(BlacklistDomain("doubleclick.net", 1L)))
        assertEquals(listOf("doubleclick.net"), db.blacklistDao().getAllDomains())
    }
}

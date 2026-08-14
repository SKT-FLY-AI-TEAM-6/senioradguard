package com.senioradguard.detector.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.senioradguard.risk.RiskLevel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UrlVerdictDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UrlVerdictDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.urlVerdictDao()
    }

    @After
    fun tearDown() = db.close()

    private fun verdict(
        url: String,
        level: RiskLevel = RiskLevel.HIGH,
        validUntil: Long = 2_000_000L
    ) = UrlVerdict(
        normalizedUrl = url,
        riskLevel = level.name,
        reason = "외부 APK 설치를 유도합니다",
        finalUrl = "https://evil.com/download",
        analyzedAt = 1_000_000L,
        validUntil = validUntil
    )

    @Test
    fun 저장한_판정을_다시_읽는다() = runTest {
        dao.upsert(verdict("https://evil.com/ad"))

        val found = dao.findValid("https://evil.com/ad", now = 1_500_000L)

        assertNotNull(found)
        assertEquals("HIGH", found!!.riskLevel)
        assertEquals("https://evil.com/download", found.finalUrl)
    }

    @Test
    fun 유효기간이_지난_판정은_조회되지_않는다() = runTest {
        dao.upsert(verdict("https://old.com/", validUntil = 1_000L))

        assertNull(dao.findValid("https://old.com/", now = 2_000L))
    }

    @Test
    fun 같은_URL로_저장하면_덮어쓴다() = runTest {
        dao.upsert(verdict("https://a.com/", level = RiskLevel.MEDIUM))
        dao.upsert(verdict("https://a.com/", level = RiskLevel.HIGH))

        val found = dao.findValid("https://a.com/", now = 0L)

        assertEquals("HIGH", found!!.riskLevel)
    }

    @Test
    fun 없는_URL은_null() = runTest {
        assertNull(dao.findValid("https://missing.com/", now = 0L))
    }

    @Test
    fun deleteExpired는_만료된_행만_지운다() = runTest {
        dao.upsert(verdict("https://old.com/", validUntil = 1_000L))
        dao.upsert(verdict("https://new.com/", validUntil = 9_000L))

        val deleted = dao.deleteExpired(now = 5_000L)

        assertEquals(1, deleted)
        assertNull(dao.findValid("https://old.com/", now = 0L))
        assertNotNull(dao.findValid("https://new.com/", now = 0L))
    }

    @Test
    fun toAssessment는_등급을_복원한다() = runTest {
        dao.upsert(verdict("https://a.com/", level = RiskLevel.MEDIUM))

        val assessment = dao.findValid("https://a.com/", now = 0L)!!.toAssessment()

        assertNotNull(assessment)
        assertEquals(RiskLevel.MEDIUM, assessment!!.level)
    }

    @Test
    fun 알_수_없는_등급_문자열은_null_평가가_된다() = runTest {
        // 앱 업데이트로 등급 체계가 바뀐 옛 행 — 재분석으로 흘러가야 한다
        dao.upsert(verdict("https://a.com/").copy(riskLevel = "LEGACY_LEVEL"))

        assertNull(dao.findValid("https://a.com/", now = 0L)!!.toAssessment())
    }

    @Test
    fun 지문_연계로_판정을_찾는다() = runTest {
        dao.upsert(verdict("https://a.com/"))
        db.adFingerprintLinkDao().upsert(AdFingerprintLink("nate.com|abc", "https://a.com/", 1L))

        val v = db.adFingerprintLinkDao().findLinkedVerdict("nate.com|abc", now = 0L)

        assertNotNull(v)
        assertEquals("HIGH", v!!.riskLevel)
        assertNull(db.adFingerprintLinkDao().findLinkedVerdict("없는지문", now = 0L))
    }

    @Test
    fun 만료된_판정은_지문으로도_나오지_않는다() = runTest {
        dao.upsert(verdict("https://a.com/", validUntil = 1_000L))
        db.adFingerprintLinkDao().upsert(AdFingerprintLink("fp", "https://a.com/", 1L))

        assertNull(db.adFingerprintLinkDao().findLinkedVerdict("fp", now = 2_000L))
    }

    @Test
    fun 기존_DAO들도_계속_동작한다() = runTest {
        db.blacklistDao().insertAll(listOf(BlacklistDomain("doubleclick.net", 1L)))
        assertEquals(listOf("doubleclick.net"), db.blacklistDao().getAllDomains())

        db.adVerdictDao().upsert(AdVerdict("k", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = 1L))
        assertNotNull(db.adVerdictDao().find("k", notBefore = 0L))
    }
}

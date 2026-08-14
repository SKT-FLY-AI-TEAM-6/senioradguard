package com.senioradguard.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UrlVerdictDao {

    /** 유효기간이 지난 판정은 반환하지 않는다 — 재분석 대상이다. */
    @Query("SELECT * FROM url_verdict WHERE normalizedUrl = :normalizedUrl AND validUntil >= :now")
    suspend fun findValid(normalizedUrl: String, now: Long): UrlVerdict?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(verdict: UrlVerdict)

    /** 만료 행 정리. ad_verdict와 함께 주 1회 워커에서 호출한다. */
    @Query("DELETE FROM url_verdict WHERE validUntil < :now")
    suspend fun deleteExpired(now: Long): Int
}

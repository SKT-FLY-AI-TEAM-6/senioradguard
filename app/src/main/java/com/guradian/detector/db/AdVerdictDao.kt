package com.guradian.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AdVerdictDao {

    /** notBefore보다 오래된 판정은 만료로 보고 반환하지 않는다. */
    @Query("SELECT * FROM ad_verdict WHERE `key` = :key AND updatedAt >= :notBefore")
    suspend fun find(key: String, notBefore: Long): AdVerdict?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(verdict: AdVerdict)

    /** 만료 행 정리. 주 1회 BlacklistUpdateWorker에서 호출한다. */
    @Query("DELETE FROM ad_verdict WHERE updatedAt < :notBefore")
    suspend fun deleteExpired(notBefore: Long): Int
}

package com.senioradguard.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UrlRiskDao {

    /** notBefore보다 오래된 판정은 만료로 보고 반환하지 않는다. */
    @Query("SELECT * FROM url_risk WHERE host = :host AND updatedAt >= :notBefore")
    suspend fun find(host: String, notBefore: Long): UrlRisk?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(risk: UrlRisk)

    /** 보호자 화면이 최근 위험 링크를 보여줄 때 쓴다. */
    @Query("SELECT * FROM url_risk ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<UrlRisk>

    @Query("DELETE FROM url_risk WHERE updatedAt < :notBefore")
    suspend fun deleteExpired(notBefore: Long): Int
}

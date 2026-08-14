package com.senioradguard.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RoiRiskDao {

    /** notBefore보다 오래된 판정은 만료로 보고 반환하지 않는다. */
    @Query("SELECT * FROM roi_risk WHERE `key` = :key AND updatedAt >= :notBefore")
    suspend fun find(key: String, notBefore: Long): RoiRisk?

    /**
     * 같은 출처에서 최근에 본 지문들. 정확히 일치하지 않는 이웃을 찾는 데 쓴다.
     *
     * 해밍 거리는 SQL로 계산할 수 없어(SQLite에 비트 카운트 함수가 없다) 후보만
     * 좁혀 받아 메모리에서 비교한다. 한 출처의 최근 판정 몇십 건이면 충분하다.
     */
    @Query(
        "SELECT * FROM roi_risk WHERE `key` LIKE :sourcePrefix || '%' " +
            "AND updatedAt >= :notBefore ORDER BY updatedAt DESC LIMIT :limit"
    )
    suspend fun recentBySource(sourcePrefix: String, notBefore: Long, limit: Int): List<RoiRisk>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(risk: RoiRisk)

    /** 보호자 화면이 최근 판정을 보여줄 때 쓴다. */
    @Query("SELECT * FROM roi_risk ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RoiRisk>

    @Query("DELETE FROM roi_risk WHERE updatedAt < :notBefore")
    suspend fun deleteExpired(notBefore: Long): Int
}

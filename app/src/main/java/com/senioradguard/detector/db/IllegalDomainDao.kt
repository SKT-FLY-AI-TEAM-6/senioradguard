package com.senioradguard.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface IllegalDomainDao {

    /**
     * 호스트의 접미사 목록을 한 번에 넘겨 조회한다.
     *
     * "ad.evil.co.kr"이면 ["ad.evil.co.kr", "evil.co.kr", "co.kr", "kr"]을 넘긴다.
     * 목록에 "evil.co.kr"만 있어도 걸리게 하려면 이 방식이어야 하고, 도메인마다
     * 쿼리를 날리는 것보다 왕복이 한 번으로 끝난다.
     *
     * 좁은 것(정확한 호스트)이 먼저 걸리도록 길이 내림차순으로 정렬한다.
     */
    @Query(
        "SELECT * FROM illegal_domain WHERE domain IN (:suffixes) " +
            "ORDER BY LENGTH(domain) DESC LIMIT 1"
    )
    suspend fun findBySuffixes(suffixes: List<String>): IllegalDomain?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<IllegalDomain>)

    @Query("SELECT COUNT(*) FROM illegal_domain")
    suspend fun count(): Int

    @Query("DELETE FROM illegal_domain")
    suspend fun clearAll()

    /** 원격 목록으로 전체 교체 (한 트랜잭션으로 원자성 보장). */
    @Transaction
    suspend fun replaceAll(rows: List<IllegalDomain>) {
        clearAll()
        insertAll(rows)
    }
}

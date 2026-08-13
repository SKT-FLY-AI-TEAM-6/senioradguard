package com.guradian.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface BlacklistDao {

    @Query("SELECT domain FROM blacklist_domains")
    suspend fun getAllDomains(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(domains: List<BlacklistDomain>)

    @Query("DELETE FROM blacklist_domains")
    suspend fun clearAll()

    /**
     * 원격 목록으로 전체 교체 (한 트랜잭션으로 원자성 보장).
     */
    @Transaction
    suspend fun replaceAll(domains: List<BlacklistDomain>) {
        clearAll()
        insertAll(domains)
    }
}

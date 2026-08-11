package com.senioradguard.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 광고 네트워크 블랙리스트 도메인 1건.
 * updatedAt은 원격 동기화 시각(epoch millis) 기록용.
 */
@Entity(tableName = "blacklist_domains")
data class BlacklistDomain(
    @PrimaryKey val domain: String,
    val updatedAt: Long
)

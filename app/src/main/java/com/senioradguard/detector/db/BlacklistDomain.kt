package com.senioradguard.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 차단 도메인 1건. 알려진 피싱·사기·광고 도메인 목록이다.
 * addedAt은 이 행이 DB에 들어온 시각(epoch millis).
 */
@Entity(tableName = "blacklist_domains")
data class BlacklistDomain(
    @PrimaryKey val domain: String,
    val addedAt: Long
)

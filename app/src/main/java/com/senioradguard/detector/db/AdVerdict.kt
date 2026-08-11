package com.senioradguard.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Layer 2(LLM) 광고 판정 캐시 1건.
 *
 * key는 "$sourceKey|$textHash" 형식이다. sourceKey는 브라우저면 도메인,
 * 앱이면 패키지명. textHash는 카드 텍스트를 정규화해 해싱한 값.
 *
 * isAd=false(광고 아님)도 저장한다. 화면의 카드 대부분은 광고가 아니므로
 * 부정 판정 캐시에서 LLM 호출 절감의 대부분이 나온다.
 */
@Entity(tableName = "ad_verdict")
data class AdVerdict(
    @PrimaryKey val key: String,
    val isAd: Boolean,
    val confidence: Float,
    /** LLM / VISION_LLM / VIEWID */
    val source: String,
    val updatedAt: Long
)

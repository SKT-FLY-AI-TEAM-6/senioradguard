package com.senioradguard.detector.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 광고 카드 지문 ↔ URL 판정 연계 (4b-④).
 *
 * URL은 클릭 후에야 생기지만 광고 카드의 문구는 클릭 전에 보인다. 판정이
 * 끝났을 때 그 광고의 지문(CardText 키 체계 — 출처 + 정규화 텍스트 해시)을
 * url_verdict 키에 묶어 두면, 같은 캠페인이 재등장했을 때 **클릭 전에**
 * 최종 등급을 표시할 수 있다.
 *
 * 소재 로테이션 대응이 핵심이다: 실측(네이트 굿리치 광고)에서 이미지는 매번
 * 바뀌었지만 문구는 같았고, 랜딩 URL은 조금씩 달랐다(page_cd 57→67).
 * URL 캐시는 빗나가도 지문은 같으므로 이 연계가 그 틈을 메운다.
 */
@Entity(tableName = "ad_fingerprint_link")
data class AdFingerprintLink(
    /** CardText.cacheKey — "출처|SHA-256(숫자 치환 정규화 텍스트)" */
    @PrimaryKey val fingerprint: String,
    /** url_verdict의 키 */
    val normalizedUrl: String,
    val updatedAt: Long
)

@Dao
interface AdFingerprintLinkDao {

    /** 지문에 연계된 유효한 판정. 판정이 만료됐으면 null — 재분석 대상이다. */
    @Query(
        """SELECT v.* FROM url_verdict v
           JOIN ad_fingerprint_link f ON f.normalizedUrl = v.normalizedUrl
           WHERE f.fingerprint = :fingerprint AND v.validUntil >= :now"""
    )
    suspend fun findLinkedVerdict(fingerprint: String, now: Long): UrlVerdict?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: AdFingerprintLink)

    /** 오래된 연계 정리. 판정 만료 정리와 함께 워커에서 호출한다. */
    @Query("DELETE FROM ad_fingerprint_link WHERE updatedAt < :notBefore")
    suspend fun deleteOlderThan(notBefore: Long): Int
}

package com.senioradguard.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 이미 확인된 불법·위험 도메인 1건. Layer 4가 판별기보다 **먼저** 보는 목록이다.
 *
 * blacklist_domains와 분리한 이유가 있다. 그쪽은 광고 네트워크 목록이고, 광고
 * 네트워크는 광고일 뿐 위험하지는 않다(doubleclick.net은 위험도 '낮음'이다).
 * 두 목록을 한 테이블에 섞으면 정상 광고 서버가 전부 '위험'으로 뜬다.
 *
 * @param domain   등록 도메인 또는 정확한 호스트. 하위 호스트도 함께 걸린다
 *                 (com.senioradguard.url.UrlParser.hostSuffixes로 조회)
 * @param category [com.senioradguard.risk.RiskCategory] 이름
 * @param score    0~100. 목록에 있다는 것은 이미 확인됐다는 뜻이라 대개 90 이상이다
 * @param note     사람이 읽는 근거 한 줄. 경고창에 그대로 나간다
 */
@Entity(tableName = "illegal_domain")
data class IllegalDomain(
    @PrimaryKey val domain: String,
    val category: String,
    val score: Int,
    val note: String,
    val updatedAt: Long
)

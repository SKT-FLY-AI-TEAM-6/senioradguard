package com.senioradguard.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Layer 4 위험도 판정 캐시 1건. 키는 호스트다.
 *
 * 광고 서버는 클릭마다 경로를 바꾸므로 URL 전체를 키로 쓰면 캐시가 한 번도 맞지
 * 않는다. 위험도는 대개 "어느 도메인인가"로 갈리므로 호스트로 묶는다.
 *
 * 위험도가 '낮음'인 판정도 저장한다. 어르신이 자주 보는 사이트는 정상 광고 서버가
 * 몇 개로 정해져 있어, 부정 판정 캐시에서 호출 절감의 대부분이 나온다.
 */
@Entity(tableName = "url_risk")
data class UrlRisk(
    @PrimaryKey val host: String,
    /** com.senioradguard.url.RiskCategory 이름 */
    val category: String,
    /** com.senioradguard.url.RiskLevel 이름 */
    val level: String,
    val score: Int,
    /** 근거들을 개행으로 이어 붙인 것. 목록 컬럼을 따로 두려고 컨버터를 쓰진 않는다 */
    val reasons: String,
    /** BLACKLIST / LLM / HEURISTIC */
    val source: String,
    val updatedAt: Long
)

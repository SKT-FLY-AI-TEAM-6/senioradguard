package com.senioradguard.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Layer 5(이미지) 위험도 판정 캐시 1건. 키는 잘라낸 영역의 **픽셀 지문**이다.
 *
 * 좌표를 키로 쓸 수 없다 — 같은 배너가 스크롤할 때마다 다른 자리에 있다.
 * 텍스트도 키로 쓸 수 없다 — 글자 없는 이미지 배너에는 키가 아예 없고, 그게 이
 * 레이어를 만든 이유다. 남는 것은 그림 자체의 지문뿐이다
 * (com.senioradguard.vision.RoiHasher).
 *
 * @param key   "$sourceKey|$dHash16진수"
 * @param hash  지문 원본. 정확히 일치하지 않는 이웃을 찾을 때 쓴다
 * @param brand 판별기가 알아본 상표. 보호자 화면에서 "누구 광고였는지"를 보여준다
 */
@Entity(tableName = "roi_risk")
data class RoiRisk(
    @PrimaryKey val key: String,
    val hash: Long,
    /** com.senioradguard.risk.RiskCategory 이름 */
    val category: String,
    /** com.senioradguard.risk.RiskLevel 이름 */
    val level: String,
    val score: Int,
    /** 근거들을 개행으로 이어 붙인 것 */
    val reasons: String,
    /** VISION / HEURISTIC / BLACKLIST */
    val source: String,
    val brand: String,
    val updatedAt: Long
)

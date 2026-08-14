package com.senioradguard.detector

import android.content.Context
import com.senioradguard.detector.db.AppDatabase
import com.senioradguard.detector.db.IllegalDomain
import com.senioradguard.detector.db.IllegalDomainDao
import com.senioradguard.risk.RiskCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Layer 4가 판별기보다 먼저 보는 불법 도메인 목록.
 *
 * ## 이 목록은 씨앗일 뿐이다
 * 아래 [SEED]는 **개발·시연용 소량 표본**이다. 불법 사이트는 차단되면 숫자만 바꿔
 * 되살아나기 때문에 앱에 박아 넣은 목록으로는 원리적으로 따라갈 수 없다. 실제
 * 운영에서는 방송통신심의위원회 차단 목록 같은 관리되는 피드를 받아
 * [replaceFromRemote]로 교체해야 한다. 지금 이 자리는 그 교체점이다.
 *
 * 목록에 없는 것을 잡는 일은 [com.senioradguard.url.UrlSignals]와 판별기가 맡는다.
 * 목록은 "확실한 것을 공짜로, 즉시" 잡는 빠른 길이지 유일한 방어선이 아니다.
 */
class IllegalDomainRepository(private val dao: IllegalDomainDao) {

    constructor(context: Context) : this(AppDatabase.getInstance(context).illegalDomainDao())

    /**
     * DB가 비어 있으면 씨앗을 넣는다. 이미 값이 있으면(원격에서 받았을 수 있다)
     * 건드리지 않는다.
     */
    suspend fun seedIfEmpty(now: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            if (dao.count() > 0) return@withContext 0
            val rows = SEED.map { it.toRow(now) }
            dao.insertAll(rows)
            rows.size
        }

    /** 관리되는 피드로 목록을 통째로 교체한다. */
    suspend fun replaceFromRemote(
        entries: List<Entry>,
        now: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext false
        dao.replaceAll(entries.map { it.toRow(now) })
        true
    }

    /** 목록 한 줄. 저장 형식(Room 엔티티)과 분리해 두어 피드 파서가 이것만 만들면 된다. */
    data class Entry(
        val domain: String,
        val category: RiskCategory,
        val score: Int,
        val note: String
    ) {
        fun toRow(now: Long) = IllegalDomain(
            domain = domain.lowercase().trimEnd('.'),
            category = category.name,
            score = score.coerceIn(0, 100),
            note = note,
            updatedAt = now
        )
    }

    companion object {

        /**
         * 시연용 표본. 국내 언론에 반복해서 보도된 유형만 담았다.
         *
         * 점수를 90 이상으로 두는 것은 "이미 확인됐다"는 뜻이다. 판별기의 추론과
         * 달리 이 판정에는 불확실성이 거의 없으므로 곧바로 '위험'으로 간다.
         */
        val SEED = listOf(
            Entry(
                "tvhot2.com", RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT, 95,
                "누누티비를 표방한 불법 다시보기 사이트 · 불법 도박 배너 다수"
            ),
            Entry(
                "noonoo.tv", RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT, 95,
                "저작권 침해 스트리밍 · 접속 차단 이력"
            ),
            Entry(
                "tvwiki.top", RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT, 92,
                "불법 다시보기 · 도메인 변경 반복"
            ),
            Entry(
                "newtoki.com", RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT, 90,
                "웹툰 불법 유통"
            ),
            Entry(
                "manatoki.net", RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT, 90,
                "만화 불법 유통"
            ),
            Entry(
                "toonkor.com", RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT, 90,
                "웹툰 불법 유통"
            ),
            Entry(
                "4shared.com", RiskCategory.UNVERIFIED_THIRD_PARTY, 50,
                "정상 클라우드지만 저작권 침해 파일 비중이 높고 첨부 파일 감염 위험"
            ),
            Entry(
                "mediafire.com", RiskCategory.UNVERIFIED_THIRD_PARTY, 45,
                "업로드 파일의 악성코드 감염 위험이 상존하는 공유 서비스"
            )
        )
    }
}

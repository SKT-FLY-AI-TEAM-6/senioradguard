package com.guradian.serp

/**
 * 검색 결과 위험도 판정의 흐름 제어. **AI 호출 트리거가 여기 전부 모여 있다.**
 *
 * 안드로이드 의존이 없어 흐름 전체를 단위 테스트로 덮을 수 있다. 노드에서 결과를
 * 뽑는 일은 [SerpScanner], 배지를 그리는 일은 [SerpBadgeOverlay]가 한다.
 *
 * ## 판별기(AI)는 네 개의 관문을 모두 통과했을 때만 호출된다
 *
 * ```
 * ① 화면 관문   크롬이고 주소가 검색 결과 페이지인가          (SerpScanner.isSearchScreen)
 *              → 아니면 스캔조차 하지 않는다. 비용 0
 *
 * ② 유휴 관문   화면 변경이 멈추고 IDLE_MS 지났는가            (SerpTracker)
 *              → 스크롤 중에는 위치만 따라가고 판별기는 안 부른다
 *
 * ③ 변경 관문   이번 화면의 결과 구성이 직전과 다른가          (signatureOf)
 *              → 같은 화면이면 캐시된 판정을 그대로 쓴다
 *
 * ④ 미지 관문   규칙으로도 캐시로도 결론이 안 난 결과가 있는가  (SerpRules / cache)
 *              → **여기 남은 것만** 배치 1회로 판별한다
 * ```
 *
 * 실제로 ④까지 내려오는 것은 많지 않다. 공식 OTT·방송사·포털은 ④의 규칙에서
 * '안전'으로 끝나고, 누누티비류는 이름만으로 '위험'이 확정돼 역시 끝난다.
 * 판별기가 필요한 것은 **처음 보는 도메인**뿐이고, 그것도 재방문하면 캐시에 걸린다.
 *
 * ## 판정은 호스트에 붙는다 — 화면과 무관하다
 * 이 클래스가 들고 있는 것은 "호스트 → 판정"이 전부다. 좌표도 화면 세대도 모른다.
 * 그래서 판별기 응답이 늦게 와도 버릴 이유가 없다 — [known]으로 조회하면 그 시점의
 * 화면에 저절로 얹힌다. 좌표를 붙이는 일은 [SerpTracker]가 한다.
 *
 * ## 왜 사용자가 버튼을 누를 때가 아닌가
 * 광고 판별(Layer 2)은 [광고 찾기] 버튼을 눌러야 돈다. 검색 결과는 그럴 수 없다 —
 * 위험도는 **누르기 전에** 보여야 의미가 있고, 검색할 때마다 버튼을 한 번 더 누르게
 * 하면 어르신은 그냥 안 누른다. 대신 나가는 정보를 줄였다: 화면 그림도, 페이지
 * 본문도 아니고 **검색 결과에 이미 공개돼 있는 제목·도메인**만 나간다.
 *
 * @param classifier   판별기. AI를 끄면 [RuleOnlyClassifier]가 들어온다
 * @param limiter      호출 상한. 시간당 [DEFAULT_CALLS_PER_HOUR]회
 * @param ttlMillis    캐시 유효기간. 도메인 평판은 변한다 — 정상이던 곳이 팔려
 *                     악성으로 바뀌는 일이 실제로 흔하다
 */
class SerpRiskEngine(
    private val classifier: SerpClassifier,
    /** 규칙 신호. 목록을 갈아끼울 때 여기로 넣는다 ([KnownSites] 참고) */
    private val signals: UrlSignals = UrlSignals.DEFAULT,
    private val limiter: SerpCallBudget = SerpCallBudget(capacity = DEFAULT_CALLS_PER_HOUR),
    private val maxEntries: Int = 300,
    private val ttlMillis: Long = 7L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis
) {

    companion object {
        /**
         * 시간당 배치 호출 상한. 배치 1회가 화면 하나이므로 곧 "시간당 검색 화면 수"다.
         * 어르신 사용 패턴에서 시간당 30번 검색하는 일은 없다 — 폭주 방지용 뚜껑이다.
         */
        const val DEFAULT_CALLS_PER_HOUR = 30

        /** 한 배치에 넣을 최대 항목 수. 화면에 보이는 결과가 보통 6~8개다. */
        const val MAX_BATCH = 8
    }

    private class Entry(val verdict: SerpVerdict, val storedAt: Long)

    /** accessOrder=true인 LinkedHashMap이 곧 LRU다. */
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    /** 직전에 판정한 화면의 지문. ③ 변경 관문이 이 값을 본다. */
    private var lastSignature: String? = null

    /**
     * @param results  판정할 결과들
     * @param verdicts 결과와 같은 순서의 판정
     * @param classifiedHosts 이번에 실제로 판별기를 부른 호스트. 로그·검증용
     */
    data class Outcome(
        val results: List<SerpResult>,
        val verdicts: List<SerpVerdict>,
        val classifiedHosts: List<String> = emptyList(),
        val skippedUnchanged: Boolean = false
    )

    /**
     * 화면 하나를 판정한다.
     *
     * @param force ③ 변경 관문을 건너뛴다. 사용자가 직접 다시 확인을 요청했을 때만 쓴다
     */
    suspend fun evaluate(
        query: String,
        results: List<SerpResult>,
        force: Boolean = false
    ): Outcome {
        if (results.isEmpty()) {
            lastSignature = null
            return Outcome(emptyList(), emptyList())
        }

        // ③ 변경 관문 — 같은 화면이면 판별기는커녕 규칙도 다시 돌리지 않는다.
        val signature = signatureOf(results)
        if (!force && signature == lastSignature) {
            val cached = results.map { cachedOf(it.host) }
            // 캐시가 만료돼 구멍이 생겼으면 지문을 무효화하고 다음 기회에 다시 본다.
            if (cached.all { it != null }) {
                return Outcome(results, cached.filterNotNull(), skippedUnchanged = true)
            }
            lastSignature = null
        }

        // ④ 미지 관문 — 규칙과 캐시로 결론이 나는 것을 먼저 걷어낸다.
        val ruleSignals = results.map { signals.of(it) }
        val resolved = arrayOfNulls<SerpVerdict>(results.size)
        val pending = mutableListOf<Int>()

        for (i in results.indices) {
            val host = results[i].host
            val byRule = SerpRules.resolve(ruleSignals[i])
            if (byRule != null) {
                resolved[i] = byRule
                // 규칙 판정도 캐시에 넣는다 — ③ 관문의 빠른 경로가 화면 전체를 캐시로
                // 채울 수 있어야 하기 때문이다. 규칙은 결정적이고 캐시보다 먼저 보므로
                // 넣어둔 값이 판정을 바꾸는 일은 없다.
                store(host, byRule)
                continue
            }
            val hit = cachedOf(host)
            if (hit != null) {
                resolved[i] = hit
                continue
            }
            pending += i
        }

        // 같은 호스트가 결과 목록에 여러 번 나오는 일이 흔하다(같은 사이트의 다른 페이지).
        // 판별은 호스트 단위이므로 한 번만 보낸다.
        val batch = LinkedHashMap<String, SerpClassifier.Request>()
        for (i in pending) {
            val r = results[i]
            if (batch.size >= MAX_BATCH) break
            batch.getOrPut(r.host) {
                SerpClassifier.Request(r.host, r.title, r.snippet, ruleSignals[i])
            }
        }

        var classified = emptyMap<String, SerpVerdict>()
        if (batch.isNotEmpty() && limiter.tryAcquire()) {
            classified = runCatching { classifier.classify(query, batch.values.toList()) }
                .getOrDefault(emptyMap())
        }

        for (i in pending) {
            val host = results[i].host
            val fromAi = classified[host]
            val verdict = RiskAggregator.combine(ruleSignals[i], fromAi)
            resolved[i] = verdict
            // 판별에 실패했으면(null) 저장하지 않는다. 규칙 판정을 캐시에 남기면
            // TTL 동안 판별기가 그 도메인을 볼 기회가 사라진다.
            if (fromAi != null) store(host, verdict)
        }

        lastSignature = signature
        return Outcome(
            results = results,
            verdicts = resolved.map { it ?: RiskAggregator.ruleVerdict(emptyList()) },
            classifiedHosts = classified.keys.toList()
        )
    }

    /**
     * 화면 지문. **호스트 구성만 본다** — 좌표는 스크롤로 계속 바뀌지만 그때마다
     * 다시 판정할 이유가 없고, 제목은 같은 결과인데도 접힘·펼침으로 흔들린다.
     */
    fun signatureOf(results: List<SerpResult>): String =
        results.map { it.host }.sorted().joinToString("|")

    /** 화면이 완전히 바뀌었다(다른 페이지로 이동). 다음 스캔을 새 화면으로 본다. */
    fun reset() {
        lastSignature = null
    }

    /**
     * 이 호스트에 대해 **지금 아는 것**. 모르면 null.
     *
     * [SerpTracker]가 배지를 그릴 때마다 부른다. 판정이 호스트에 붙어 있고 화면과
     * 무관하다는 것이 이 함수 하나로 드러난다 — 좌표도, 화면 세대도 묻지 않는다.
     * 그래서 스크롤 도중에도, 판별기 응답이 5초 뒤에 와도 같은 답을 준다.
     */
    fun known(host: String): SerpVerdict? = cachedOf(host)

    private fun cachedOf(host: String): SerpVerdict? = synchronized(cache) {
        val entry = cache[host] ?: return@synchronized null
        if (now() - entry.storedAt >= ttlMillis) {
            cache.remove(host)
            return@synchronized null
        }
        entry.verdict.copy(source = SerpVerdict.SOURCE_CACHE)
    }

    private fun store(host: String, verdict: SerpVerdict) = synchronized(cache) {
        cache[host] = Entry(verdict, now())
        Unit
    }

    /** 테스트·진단용. */
    fun cacheSize(): Int = synchronized(cache) { cache.size }
}

package com.guradian.agent

import java.security.MessageDigest

/**
 * 카드 텍스트를 캐시 키로 바꾸고, 외부로 보내기 전에 개인정보를 지운다.
 *
 * 안드로이드 의존이 없는 순수 함수라 단위 테스트로 전부 덮는다. 마스킹은 화면
 * 내용을 외부 서버로 보내기 전 마지막 방어선이라 여기서 새면 되돌릴 수 없다.
 */
object CardText {

    /** LLM 입력 길이 상한. 넘는 부분은 자른다 (비용·지연 제어). */
    private const val MAX_INPUT_CHARS = 400

    /** 캐시 키 계산에 쓰는 정규화 텍스트 길이. */
    private const val HASH_PREFIX_CHARS = 200

    // 마스킹은 긴 패턴부터 적용해야 한다. 전화번호 규칙이 먼저 돌면 카드번호
    // 앞자리를 먹어치워 카드번호 패턴이 더 이상 매칭되지 않는다.
    private val maskRules = listOf(
        // 주민등록번호 — 6자리 + 7자리
        Regex("""\b\d{6}[-\s]?[1-4]\d{6}\b""") to "[주민번호]",
        // 카드번호 — 4자리 4묶음
        Regex("""\b(?:\d{4}[-\s]?){3}\d{4}\b""") to "[카드번호]",
        // 휴대폰 번호
        Regex("""\b01\d[-\s]?\d{3,4}[-\s]?\d{4}\b""") to "[전화번호]"
    )

    /** 개인정보 패턴을 지운다. 외부 전송 전 반드시 통과시킨다. */
    fun mask(text: String): String =
        maskRules.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /**
     * 캐시 키용 정규화.
     *
     * 숫자를 '#'으로 치환하는 게 핵심이다. 카운트다운·가격·조회수는 볼 때마다
     * 달라서, 그대로 두면 같은 광고인데 매번 캐시 미스가 나 LLM을 다시 부른다.
     */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("""\d"""), "#")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(HASH_PREFIX_CHARS)

    /** 캐시 키 — "$sourceKey|$textHash". 같은 출처의 같은 문구는 같은 키가 된다. */
    fun cacheKey(sourceKey: String, texts: List<String>): String {
        val normalized = normalize(texts.joinToString(" "))
        return "$sourceKey|${sha256(normalized)}"
    }

    /** LLM에 보낼 텍스트 — 마스킹 후 400자로 절단. */
    fun forClassifier(texts: List<String>): String =
        mask(texts.joinToString(" ").replace(Regex("""\s+"""), " ").trim())
            .take(MAX_INPUT_CHARS)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

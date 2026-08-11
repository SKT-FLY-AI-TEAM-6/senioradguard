package com.senioradguard.detector

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * AdDetector
 *
 * 3가지 탐지 전략을 결합해 0.0~1.0 신뢰도 점수를 반환합니다.
 *
 *   scoreByKeywords()  — 텍스트 키워드 패턴 (< 1ms, 항상 실행)
 *   scoreByBlacklist() — 도메인 DB 조회 (< 5ms, URL 있을 때)
 *   scoreByAI()        — ML Kit OCR + 분류 (< 200ms, 의심 시 실행)
 */
class AdDetector(private val context: Context) {

    // 앱 설치 유도 키워드 → 가중치
    private val keywordWeights = mapOf(
        "설치하기" to 1.0f, "지금 설치" to 1.0f, "무료 설치" to 0.9f,
        "무료 다운로드" to 0.9f, "앱 다운로드" to 0.85f,
        "지금 받기" to 0.8f, "혜택 받기" to 0.7f,
        "이벤트 참여" to 0.6f, "광고" to 0.5f,
        "install now" to 1.0f, "free download" to 0.9f,
        "get the app" to 0.85f, "download now" to 0.9f
    )

    // 유튜브/인스타그램 — 정상적인 광고가 섞여 나오는 앱에서 감지할 네이티브 광고 레이블.
    // 일반 keywordWeights와 달리 점수 없이 단순 매칭용 (차단 팝업이 아니라 정보 배너 트리거).
    private val adLabelPackages: Map<String, Set<String>> = mapOf(
        "com.google.android.youtube" to setOf(
            "광고", "Ad", "광고 건너뛰기", "건너뛰기", "5초 후 건너뛸 수 있습니다"
        ),
        "com.instagram.android" to setOf(
            "광고", "Sponsored"
        )
    )

    // 도메인 블랙리스트 (Room DB 캐시, WorkManager가 주 1회 원격 목록으로 갱신)
    private val blacklistRepository = BlacklistRepository(context)

    private val textRecognizer = TextRecognition.getClient(
        KoreanTextRecognizerOptions.Builder().build()
    )

    // ──────────────────────────────────────
    // 1. 키워드 패턴 매칭
    // ──────────────────────────────────────

    /**
     * 텍스트에서 광고 키워드를 찾아 0.0~1.0 점수 반환.
     * 여러 키워드 매칭 시 최대값을 반환 (합산 아님).
     */
    fun scoreByKeywords(text: String): Float {
        var maxScore = 0f
        for ((keyword, weight) in keywordWeights) {
            if (text.contains(keyword, ignoreCase = true)) {
                maxScore = maxOf(maxScore, weight)
            }
        }
        return maxScore
    }

    // ──────────────────────────────────────
    // 1-1. 유튜브/인스타그램 네이티브 광고 레이블 매칭
    // ──────────────────────────────────────

    /** 이 패키지가 광고 레이블 배너 감지 대상인지 여부 (유튜브/인스타그램 등). */
    fun isAdLabelPackage(packageName: String): Boolean = packageName in adLabelPackages

    /**
     * 화면에서 수집한 텍스트 목록에 해당 패키지의 광고 레이블 문구가 포함돼 있는지 확인한다.
     * scoreByKeywords와 달리 가중치 없는 단순 boolean 매칭 — 차단이 아니라 정보 배너 트리거용.
     */
    fun matchesAdLabel(packageName: String, texts: List<String>): Boolean {
        val labels = adLabelPackages[packageName] ?: return false
        return texts.any { text -> labels.any { label -> text.contains(label, ignoreCase = true) } }
    }

    // ──────────────────────────────────────
    // 2. 도메인 블랙리스트
    // ──────────────────────────────────────

    /**
     * URL 목록 중 블랙리스트 도메인 포함 여부 확인.
     * 하나라도 매칭되면 1.0, 없으면 0.0 반환.
     */
    suspend fun scoreByBlacklist(urls: List<String>): Float {
        if (urls.isEmpty()) return 0f
        val domains = BlacklistCache.domains { blacklistRepository.getDomains() }
        for (url in urls) {
            val domain = extractDomain(url) ?: continue
            if (DomainMatcher.isBlocked(domain, domains)) return 1.0f
        }
        return 0f
    }

    private fun extractDomain(url: String): String? = runCatching {
        java.net.URL(url).host.removePrefix("www.")
    }.getOrNull()

    // ──────────────────────────────────────
    // 3. AI 이미지 분석 (ML Kit OCR)
    // ──────────────────────────────────────

    /**
     * 스크린샷을 ML Kit OCR로 텍스트 추출 후 키워드 재검사.
     * 추후 TFLite 커스텀 모델(광고 배너 분류기)로 교체 가능.
     */
    suspend fun scoreByAI(bitmap: Bitmap): Float =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { result ->
                    val extractedText = result.textBlocks
                        .joinToString(" ") { it.text }
                    val score = scoreByKeywords(extractedText)
                    cont.resume(score)
                }
                .addOnFailureListener {
                    cont.resume(0f) // AI 실패 시 영향 없음
                }
        }

}

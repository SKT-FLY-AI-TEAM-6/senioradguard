package com.senioradguard.analysis

import com.senioradguard.risk.RiskAssessment
import com.senioradguard.risk.RiskLevel

/**
 * 수집된 페이지 신호 → 위험 등급. 순수 함수라 JVM 단위 테스트 대상이다.
 *
 * 온디바이스 LLM(4c)이 붙기 전의 규칙 기반 1차 판정기이자, LLM이 붙은 뒤에도
 * 남는 안전핀이다 — **LLM 단독으로는 저위험을 확정할 수 없다**(프롬프트 주입
 * 방어, 설계 문서 3.3절). 여기 규칙이 위험을 잡으면 LLM이 뭐라 하든 등급을
 * 내리지 못한다.
 *
 * 판단 기준은 확정 기획 3.1절의 예시를 코드로 옮긴 것이다:
 * 고위험 = 외부 APK 설치, 개인정보·결제정보 요구 등 명확한 신호
 * 중위험 = 과도한 할인·과장, 낯선 도메인, 반복 리다이렉트 등 의심 신호
 */
object UrlRiskRules {

    /** 과장·유인 문구. 하나만으로는 약한 신호다 — 정상 쇼핑몰에도 흔하다. */
    private val hypeWords = listOf(
        "100% 당첨", "무료 체험", "원금 보장", "고수익", "지금 즉시", "오늘만",
        "선착순", "마감 임박", "정부 지원금"
    )

    /** 개인정보 리드 수집 폼의 전형적 입력 이름 */
    private val phoneInputPattern = Regex(
        """<input[^>]*(type=["']?tel|name=["']?(phone|tel|hp|mobile|contact)|id=["']?(phone|tel|hp|mobile))""",
        RegexOption.IGNORE_CASE
    )

    private val leadWords = listOf("상담신청", "상담 신청", "무료상담", "무료 상담", "신청하기", "가입하기", "이벤트 응모")

    private val apkLinkPattern = Regex("""\.apk["'\s?#)]""", RegexOption.IGNORE_CASE)

    private val passwordInputPattern =
        Regex("""<input[^>]*type=["']?password""", RegexOption.IGNORE_CASE)

    private val cardInputPattern = Regex(
        """<input[^>]*(autocomplete=["']?cc-|name=["']?(card_?(no|num)|cc_?num))""",
        RegexOption.IGNORE_CASE
    )

    /**
     * @param finalUrl 리다이렉트를 따라간 최종 URL
     * @param redirectHops 거친 리다이렉트 횟수
     * @param contentType 최종 응답의 Content-Type (모르면 null)
     * @param html 최종 페이지 본문 (HTML이 아니거나 못 읽었으면 null)
     */
    fun assess(
        finalUrl: String,
        redirectHops: Int,
        contentType: String?,
        html: String?
    ): RiskAssessment.Assessed {
        val url = finalUrl.lowercase()
        val body = html.orEmpty()

        // ── 고위험: 명확한 위험 신호 ──

        if (contentType?.contains("vnd.android.package-archive") == true ||
            url.substringBefore('?').endsWith(".apk")
        ) {
            return high("앱 설치 파일(.apk)로 바로 연결되는 주소예요")
        }
        if (apkLinkPattern.containsMatchIn(body)) {
            return high("페이지 안에 앱 설치 파일(.apk) 링크가 있어요")
        }
        if (passwordInputPattern.containsMatchIn(body)) {
            // 광고로 도착한 페이지가 비밀번호부터 요구하는 것은 전형적인 피싱 모양이다.
            // (정상 서비스 로그인은 광고 랜딩이 아니라 사용자가 찾아간 화면에 있다)
            return high("광고로 연 페이지가 비밀번호 입력을 요구해요")
        }
        if (cardInputPattern.containsMatchIn(body)) {
            return high("카드번호 입력을 요구하는 페이지예요")
        }
        if (body.contains("주민등록번호") && body.contains("<input", ignoreCase = true)) {
            return high("주민등록번호 입력을 요구하는 페이지예요")
        }

        // ── 중위험: 의심 신호 ──

        val hasLeadForm = body.contains("<form", ignoreCase = true) &&
            phoneInputPattern.containsMatchIn(body) &&
            leadWords.any { it in body }
        if (hasLeadForm) {
            return medium("이름·전화번호 입력을 유도하는 페이지예요")
        }

        val weak = mutableListOf<String>()
        if (url.startsWith("http://")) weak += "보안 연결(https)이 아니에요"
        if (redirectHops >= 3) weak += "주소가 여러 번 바뀌며 연결됐어요"
        if (hypeWords.any { it in body }) weak += "과장 문구가 보여요"
        if (weak.size >= 2) {
            return medium("의심스러운 점이 있어요 — " + weak.joinToString(", "))
        }

        // ── 저위험: 위 신호가 하나도 확인되지 않음 ──
        // "안전하다"가 아니라 "확인한 범위에서 위험 신호가 없다"이다.
        return RiskAssessment.Assessed(RiskLevel.LOW, "확인한 범위에서 위험 신호가 없어요")
    }

    private fun high(reason: String) = RiskAssessment.Assessed(RiskLevel.HIGH, reason)
    private fun medium(reason: String) = RiskAssessment.Assessed(RiskLevel.MEDIUM, reason)

    /**
     * 판정의 유효기간. 피싱 사이트는 수명이 짧아 위험할수록 짧게 잡는다 —
     * 고위험 도메인이 며칠 뒤 정상 페이지로 바뀌어 있을 수 있고(재분석 필요),
     * 반대로 안전 판정은 오래 믿어도 손해가 작다.
     */
    fun validityMs(level: RiskLevel): Long = when (level) {
        RiskLevel.HIGH -> 3L * 24 * 60 * 60 * 1000
        RiskLevel.MEDIUM -> 7L * 24 * 60 * 60 * 1000
        RiskLevel.LOW -> 30L * 24 * 60 * 60 * 1000
    }
}

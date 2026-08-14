package com.senioradguard.analysis

/**
 * 광고 카드에서 **광고주 표기줄**을 골라낸다 — 거친(2차) 지문의 재료다.
 *
 * 같은 캠페인이 문구를 계속 바꿔 돌려도(실측: 굿리치 광고 헤드라인 변형 4종)
 * 심의·등록 표기줄("굿리치 [등록번호:제####호]", "손해보험협회 심의필 제###호")은
 * 광고주 단위로 동일하다. 헤드라인 기반 1차 지문이 변형마다 빗나갈 때
 * 이 줄로 만든 지문이 캠페인 전체를 이어준다.
 *
 * 순수 함수 — JVM 단위 테스트 대상.
 */
object AdvertiserMark {

    /** 광고 심의·등록 표기에 쓰이는 낱말들. 일반 기사 문장에는 사실상 안 나온다. */
    private val complianceMarks = listOf(
        "등록번호", "심의필", "심의번호", "사업자등록", "준법감시", "광고심의"
    )

    /**
     * 도메인 꼴의 한 줄 (구글 디스플레이 광고가 광고주 표시로 쓰는
     * "bo-gung.co.kr" 형태). 줄 전체가 도메인일 때만 인정한다 — 문장 속에
     * 낀 주소를 잡으면 기사 문장이 광고주 줄로 오인된다.
     */
    private val domainLine = Regex("""[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+""")

    /**
     * 광고주 표기줄만 골라낸다. 없으면 빈 목록 — 거친 지문을 만들지 않는다.
     *
     * 여기 걸리는 줄만 쓰는 이유: 거친 지문이 서로 다른 광고끼리 충돌하면
     * 엉뚱한 광고에 위험 표시가 붙는다(오탐이 미탐보다 치명적). 심의 표기와
     * 도메인 줄은 광고주 고유라 충돌하지 않지만, "지금 확인하세요" 같은
     * 흔한 문구를 재료로 쓰면 곧바로 충돌한다.
     */
    fun advertiserLines(texts: List<String>): List<String> {
        val compliance = texts.filter { line -> complianceMarks.any { it in line } }
        if (compliance.isNotEmpty()) return compliance
        return texts.filter { domainLine.matches(it.trim()) }
    }
}

package com.senioradguard.url

/**
 * 광고를 눌렀을 때 이동하는 링크 하나. Layer 4의 입력 스키마다.
 *
 * 필드 구성은 팀에서 합의한 JSON 스키마를 그대로 옮긴 것이다.
 *
 * ```json
 * {
 *   "target_url": "https://ad.yna.co.kr/RealMedia/ads/click_lx.ads/...",
 *   "url_components": {
 *     "protocol": "https", "domain": "ad.yna.co.kr", "root_domain": "yna.co.kr",
 *     "tld": "co.kr", "subdomain": "ad", "path": "/RealMedia/ads/click_lx.ads/..."
 *   },
 *   "context": {
 *     "source_page_url": "https://www.yna.co.kr",
 *     "anchor_text": "삼성전자 신형 스마트폰 특가",
 *     "is_ad_element": true,
 *     "is_shortener": false
 *   }
 * }
 * ```
 *
 * **URL만 본다. 실제로 접속하지 않는다.** 접속을 못 하게 막아둔 도메인이 있고,
 * 어르신 회선으로 낯선 서버에 요청을 보내는 것 자체가 위험을 만든다(추적 픽셀,
 * 리다이렉트 체인, 데이터 요금). 판단 근거를 URL 문자열과 출처 문맥으로 한정하는
 * 것은 타협이 아니라 이 기능의 전제다.
 */
data class AdLink(
    val targetUrl: String,
    val components: UrlComponents,
    val context: LinkContext
) {
    /**
     * 캐시·블랙리스트 조회 키. 호스트 단위다.
     *
     * 경로까지 키에 넣으면 광고 서버가 클릭마다 다른 경로를 주기 때문에 캐시가
     * 사실상 동작하지 않는다. 위험도는 대개 "어느 도메인인가"로 갈리므로 호스트로
     * 묶는다. 같은 호스트에 안전한 페이지와 위험한 페이지가 섞인 공유 호스팅은
     * 이 방식으로 구분되지 않는다 — 그런 곳은 도메인 신뢰도 축에서 함께 낮게 잡힌다.
     */
    val cacheKey: String get() = components.domain
}

/**
 * URL을 구성 요소로 분해한 결과.
 *
 * @param protocol  "https" / "http". 스킴이 없으면 빈 문자열
 * @param domain    전체 호스트 ("ad.yna.co.kr")
 * @param rootDomain 등록 가능 도메인 ("yna.co.kr") — 위험도 판정의 기준 단위
 * @param tld       공개 접미사 ("co.kr"). IP 주소면 빈 문자열
 * @param subdomain rootDomain 앞에 붙은 부분 ("ad"). 없으면 빈 문자열
 * @param path      호스트 뒤 전체. **쿼리·프래그먼트를 포함한다** — 추적 파라미터와
 *                  유인 문구가 거기 들어 있어 잘라내면 판단 근거가 사라진다
 */
data class UrlComponents(
    val protocol: String,
    val domain: String,
    val rootDomain: String,
    val tld: String,
    val subdomain: String,
    val path: String
)

/**
 * 링크가 발견된 문맥. URL 문자열만으로는 안 보이는 것을 채운다.
 *
 * @param sourcePageUrl 링크가 있던 페이지(브라우저) 또는 패키지명(앱)
 * @param anchorText    링크 문구. 같은 도메인이라도 "본인인증 하세요"와 "기사 더보기"는
 *                      전혀 다른 것을 노린다
 * @param isAdElement   Layer 1·2가 광고로 표시한 영역 안에서 나온 링크인가
 * @param isShortener   bit.ly 같은 단축 주소인가 — 최종 목적지를 숨기는 행위 자체가 신호
 */
data class LinkContext(
    val sourcePageUrl: String,
    val anchorText: String,
    val isAdElement: Boolean,
    val isShortener: Boolean
)

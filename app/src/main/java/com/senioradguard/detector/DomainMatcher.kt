package com.senioradguard.detector

import com.senioradguard.url.UrlParser

/**
 * 호스트명이 차단 목록에 해당하는지 판정한다.
 *
 * 목록이 14만 건 규모라 endsWith 선형 탐색은 쓸 수 없다. 호스트를 라벨 단위로
 * 뒤에서부터 잘라 올라가며 HashSet 정확 일치로 조회하면 최대 라벨 수(보통 4~5)
 * 번만에 끝난다.
 *
 * 라벨 경계에서만 자르므로 "notdoubleclick.net"이 "doubleclick.net"에 걸리는
 * endsWith 방식의 오탐도 함께 사라진다.
 *
 * 접미사를 만드는 부분은 Layer 4의 불법 도메인 조회와 같은 것이라
 * [UrlParser.hostSuffixes]로 합쳐 두었다. 두 곳이 다른 규칙으로 자르면 같은
 * 도메인이 한쪽 목록에만 걸리는 일이 생긴다.
 */
object DomainMatcher {

    fun isBlocked(host: String, blocked: Set<String>): Boolean {
        if (host.isEmpty() || blocked.isEmpty()) return false
        return UrlParser.hostSuffixes(host).any { it in blocked }
    }
}

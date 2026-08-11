package com.senioradguard.detector

/**
 * 호스트명이 차단 목록에 해당하는지 판정한다.
 *
 * 목록이 14만 건 규모라 endsWith 선형 탐색은 쓸 수 없다. 호스트를 라벨 단위로
 * 뒤에서부터 잘라 올라가며 HashSet 정확 일치로 조회하면 최대 라벨 수(보통 4~5)
 * 번만에 끝난다.
 *
 * 라벨 경계에서만 자르므로 "notdoubleclick.net"이 "doubleclick.net"에 걸리는
 * endsWith 방식의 오탐도 함께 사라진다.
 */
object DomainMatcher {

    fun isBlocked(host: String, blocked: Set<String>): Boolean {
        if (host.isEmpty() || blocked.isEmpty()) return false

        val normalized = host.lowercase().trimEnd('.')
        if (normalized.isEmpty()) return false

        var index = 0
        while (index >= 0 && index < normalized.length) {
            if (normalized.substring(index) in blocked) return true
            val next = normalized.indexOf('.', index)
            index = if (next < 0) -1 else next + 1
        }
        return false
    }
}

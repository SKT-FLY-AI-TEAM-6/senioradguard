package com.senioradguard.agent

import android.graphics.Rect

/**
 * Layer 2가 판별할 후보 카드 하나.
 *
 * 개별 노드가 아니라 카드 단위다. 피드 한 화면의 노드는 수백 개지만 카드는
 * 5~15개 수준이라, 카드로 묶어야 LLM 호출 수가 감당 가능한 범위에 들어온다.
 *
 * @param rect      화면상 영역. 점선 테두리를 그릴 자리이자 캐시 히트 시 표시 위치
 * @param texts     카드 안의 텍스트 조각들 (마스킹 전 원문)
 * @param viewIds   viewIdResourceName 모음 — Agent4 교차검증용 약한 신호
 * @param sourceKey 브라우저면 도메인, 앱이면 패키지명. 캐시 키의 앞부분
 */
data class AdCandidate(
    val rect: Rect,
    val texts: List<String>,
    val viewIds: List<String>,
    val sourceKey: String
)

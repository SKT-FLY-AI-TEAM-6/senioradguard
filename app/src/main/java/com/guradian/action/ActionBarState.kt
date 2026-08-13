package com.guradian.action

import com.guradian.rule.EscapeReason

/**
 * 액션바의 주 버튼. **한 번에 하나만 뜬다.**
 *
 * 어르신에게 버튼 세 개를 동시에 주면 무엇을 눌러야 할지 고르는 일 자체가 부담이다.
 * 지금 상황에서 가장 필요한 하나만 크게 보여주고 나머지는 감춘다.
 */
enum class PrimaryAction { ESCAPE, CLOSE_AD, FIND_AD, BUSY, NONE }

/**
 * 주 버튼 상태 머신. 순수 함수라 단위 테스트로 전부 덮는다.
 *
 * 우선순위: **BUSY > ESCAPE > CLOSE_AD > FIND_AD > NONE**
 *
 *  - `busy`가 전부를 이긴다 — 이미 누른 동작이 도는 중에 버튼이 다른 것으로
 *    바뀌면 손 밑에서 기능이 갈리고, 두 번째 누름이 엉뚱한 동작을 실행한다.
 *  - `escape`가 광고 표시보다 앞선다 — 끌려간 상태에서 "광고 닫기"는 의미가 없다.
 *  - `aiEnabled`가 꺼져 있으면 FIND_AD는 아예 나오지 않는다. 눌러봐야 아무 일도
 *    일어나지 않는 버튼을 보여주는 것은 고장으로 읽힌다.
 */
data class ActionBarState(
    val escape: EscapeReason? = null,
    val adRegionCount: Int = 0,
    val busy: Boolean = false,
    val aiEnabled: Boolean = false
) {
    val primary: PrimaryAction
        get() = when {
            busy -> PrimaryAction.BUSY
            escape != null -> PrimaryAction.ESCAPE
            adRegionCount > 0 -> PrimaryAction.CLOSE_AD
            aiEnabled -> PrimaryAction.FIND_AD
            else -> PrimaryAction.NONE
        }
}

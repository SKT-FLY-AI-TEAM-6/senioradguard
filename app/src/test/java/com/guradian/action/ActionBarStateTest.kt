package com.guradian.action

import com.guradian.rule.EscapeReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionBarStateTest {

    // ── 우선순위 5케이스 ─────────────────────────────────────

    @Test
    fun `탈출 상황이면 돌아가기`() {
        val state = ActionBarState(
            escape = EscapeReason.STORE_REDIRECT,
            adRegionCount = 3,
            aiEnabled = true
        )
        assertEquals(PrimaryAction.ESCAPE, state.primary)
    }

    @Test
    fun `광고 테두리가 있으면 광고 닫기`() {
        val state = ActionBarState(adRegionCount = 1, aiEnabled = true)
        assertEquals(PrimaryAction.CLOSE_AD, state.primary)
    }

    @Test
    fun `그 외에는 광고 찾기`() {
        val state = ActionBarState(aiEnabled = true)
        assertEquals(PrimaryAction.FIND_AD, state.primary)
    }

    @Test
    fun `아무 조건도 없으면 버튼이 없다`() {
        assertEquals(PrimaryAction.NONE, ActionBarState().primary)
    }

    // 손 밑에서 버튼이 다른 기능으로 바뀌면 두 번째 누름이 엉뚱한 동작을 실행한다
    @Test
    fun `진행 중이면 전부를 이긴다`() {
        val state = ActionBarState(
            escape = EscapeReason.INSTALL_TRIGGER,
            adRegionCount = 5,
            busy = true,
            aiEnabled = true
        )
        assertEquals(PrimaryAction.BUSY, state.primary)
    }

    // ── aiEnabled ────────────────────────────────────────────

    // 눌러도 아무 일이 없는 버튼은 고장으로 읽힌다
    @Test
    fun `AI 판별이 꺼져 있으면 광고 찾기가 나오지 않는다`() {
        assertEquals(PrimaryAction.NONE, ActionBarState(aiEnabled = false).primary)
    }

    @Test
    fun `AI 판별이 꺼져 있어도 광고 닫기는 나온다`() {
        val state = ActionBarState(adRegionCount = 2, aiEnabled = false)
        assertEquals(PrimaryAction.CLOSE_AD, state.primary)
    }

    @Test
    fun `AI 판별이 꺼져 있어도 돌아가기는 나온다`() {
        val state = ActionBarState(escape = EscapeReason.MALICIOUS_URL, aiEnabled = false)
        assertEquals(PrimaryAction.ESCAPE, state.primary)
    }

    // ── 경계 ────────────────────────────────────────────────

    @Test
    fun `광고가 0건이면 광고 닫기가 나오지 않는다`() {
        val state = ActionBarState(adRegionCount = 0, aiEnabled = true)
        assertEquals(PrimaryAction.FIND_AD, state.primary)
    }

    @Test
    fun `탈출 사유가 무엇이든 돌아가기다`() {
        EscapeReason.values().forEach { reason ->
            assertEquals(PrimaryAction.ESCAPE, ActionBarState(escape = reason).primary)
        }
    }
}

package com.senioradguard.region

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdLabelRulesTest {

    // ── isAdLabel ────────────────────────────────────────────

    @Test
    fun `단독 광고 표기를 인식한다`() {
        assertTrue(AdLabelRules.isAdLabel("광고"))
        assertTrue(AdLabelRules.isAdLabel("스폰서"))
        assertTrue(AdLabelRules.isAdLabel("Sponsored"))
        assertTrue(AdLabelRules.isAdLabel("협찬 광고"))
        assertTrue(AdLabelRules.isAdLabel("이웃광고"))
        assertTrue(AdLabelRules.isAdLabel("AD"))
        assertTrue(AdLabelRules.isAdLabel("advertisement"))
    }

    @Test
    fun `대소문자를 무시한다`() {
        assertTrue(AdLabelRules.isAdLabel("SPONSORED"))
        assertTrue(AdLabelRules.isAdLabel("Ad"))
    }

    // 유튜브 Litho는 광고 카드 전체를 한 노드로 합쳐 설명 문구 안에 라벨을 섞는다
    @Test
    fun `구분자로 쪼갠 토큰이 라벨이면 인식한다`() {
        assertTrue(AdLabelRules.isAdLabel("어떤 채널 · 광고 · 조회수 1만회"))
        assertTrue(AdLabelRules.isAdLabel("브랜드명, Sponsored"))
        assertTrue(AdLabelRules.isAdLabel("제목 - 광고"))
        assertTrue(AdLabelRules.isAdLabel("제목 • AD"))
    }

    // 제목 속에 우연히 들어간 단어는 오탐이면 안 된다
    @Test
    fun `제목 안에 포함된 단어는 오탐하지 않는다`() {
        assertFalse(AdLabelRules.isAdLabel("광고학개론 강의 1강"))
        assertFalse(AdLabelRules.isAdLabel("Sponsored content marketing guide"))
        assertFalse(AdLabelRules.isAdLabel("Bad news today"))
    }

    // " - "는 양옆 공백이 있을 때만 구분자 — 단어 속 하이픈은 쪼개면 안 된다
    @Test
    fun `단어 속 하이픈은 구분자로 취급하지 않는다`() {
        assertFalse(AdLabelRules.isAdLabel("non-sponsored"))
        assertFalse(AdLabelRules.isAdLabel("anti-ad blocker"))
    }

    // 웹 광고는 문구 사이에 폭 0 문자를 끼워 차단을 회피한다
    @Test
    fun `폭 0 문자가 끼어 있어도 인식한다`() {
        assertTrue(AdLabelRules.isAdLabel("광\u200b고"))
        assertTrue(AdLabelRules.isAdLabel("Spon\u200csored"))
        assertTrue(AdLabelRules.isAdLabel("\ufeff광고"))
    }

    @Test
    fun `빈 문자열과 무관한 텍스트는 false`() {
        assertFalse(AdLabelRules.isAdLabel(""))
        assertFalse(AdLabelRules.isAdLabel("오늘의 날씨"))
    }

    // ── isAdContainer ────────────────────────────────────────

    @Test
    fun `알려진 광고 네트워크 id를 인식한다`() {
        assertTrue(AdLabelRules.isAdContainer("div-gpt-ad-12345"))
        assertTrue(AdLabelRules.isAdContainer("adsbygoogle"))
        assertTrue(AdLabelRules.isAdContainer("google_ads_iframe_1"))
        assertTrue(AdLabelRules.isAdContainer("adfit_banner"))
        assertTrue(AdLabelRules.isAdContainer("CRITEO_slot"))
    }

    @Test
    fun `무관한 id와 null은 false`() {
        assertFalse(AdLabelRules.isAdContainer("main_content"))
        assertFalse(AdLabelRules.isAdContainer("com.android.chrome:id/url_bar"))
        assertFalse(AdLabelRules.isAdContainer(null))
        assertFalse(AdLabelRules.isAdContainer(""))
    }
}

package com.senioradguard.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertiserMarkTest {

    @Test
    fun 등록_심의_표기줄만_골라낸다() {
        // 실측: 굿리치 popIn 광고의 실제 구조 — 헤드라인은 변형마다 바뀌고
        // 광고주 표기줄은 동일하다
        val creative1 = listOf("새로 나온 '실손보험' 난리 난 이유!", "굿리치 [등록번호:제2006038313호]")
        val creative2 = listOf("'실손보험' 최적가! 지금 바로 비교하세요", "굿리치 [등록번호:제2006038313호]")

        assertEquals(
            AdvertiserMark.advertiserLines(creative1),
            AdvertiserMark.advertiserLines(creative2)
        )
        assertEquals(listOf("굿리치 [등록번호:제2006038313호]"), AdvertiserMark.advertiserLines(creative1))
    }

    @Test
    fun 심의필_표기도_잡는다() {
        val texts = listOf("AXA 자동차보험 맞춤 상담", "손해보험협회 심의필 제 171605호")
        assertEquals(listOf("손해보험협회 심의필 제 171605호"), AdvertiserMark.advertiserLines(texts))
    }

    @Test
    fun 표기줄이_없으면_빈_목록() {
        assertTrue(AdvertiserMark.advertiserLines(listOf("오늘의 날씨", "전국 맑음")).isEmpty())
    }
}

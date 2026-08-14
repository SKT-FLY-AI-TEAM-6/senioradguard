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

    @Test
    fun 도메인_표기줄도_광고주_줄이다() {
        // 실측: 구글 디스플레이 광고(보궁)의 실제 구조 — 문구는 변형마다 바뀌고
        // 광고주 표시는 도메인 한 줄이다
        val creative1 = listOf("다가오는 가을 관절 건강 미리 준비하세요", "bo-gung.co.kr", "열기")
        val creative2 = listOf("관절 통증, 이제 그만", "bo-gung.co.kr", "Open")

        assertEquals(
            AdvertiserMark.advertiserLines(creative1),
            AdvertiserMark.advertiserLines(creative2)
        )
        assertEquals(listOf("bo-gung.co.kr"), AdvertiserMark.advertiserLines(creative1))
    }

    @Test
    fun 문장_속_주소는_광고주_줄이_아니다() {
        // 기사 문장에 주소가 섞인 경우 — 줄 전체가 도메인일 때만 인정한다
        assertTrue(
            AdvertiserMark.advertiserLines(
                listOf("자세한 내용은 nate.com 공지에서 확인하세요")
            ).isEmpty()
        )
    }
}

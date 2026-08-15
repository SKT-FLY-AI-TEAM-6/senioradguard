package com.guradian.serp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 표본 27건에 **규칙만** 돌려 본다. 실제 Gemini 판정과 같은 표본이다
 * (2026-08-14 실측: 판별기는 26/27 · 오탐 0 · 미탐 0).
 *
 * 두 가지를 지킨다.
 *
 *  1. **규칙이 확정한 것은 틀리지 않는다.** 규칙이 결론을 내면 판별기는 불리지
 *     않으므로, 여기서 틀리면 고칠 기회가 영영 없다. 확신이 없으면 규칙은 null을
 *     내고 판별기에 넘겨야 한다 — 그것이 [SerpRules]의 계약이다.
 *  2. **공짜로 끝나는 비율이 유지된다.** 규칙이 끝내는 만큼 호출이 줄어든다.
 *     이 수치가 떨어지면 검색 한 번의 비용이 그만큼 올라간다.
 */
class SerpSampleTest {

    private val signals = UrlSignals.DEFAULT

    /** (호스트, 제목, 설명, 사람이 본 정답 등급) */
    private val samples = listOf(
        // ── 정상: 공식 OTT·방송·포털 ──
        s("tving.com", "TVING - 티빙", "오리지널 시리즈, 방송 다시보기, 실시간 TV", RiskGrade.LOW),
        s("wavve.com", "웨이브 - 지상파 드라마 다시보기", "KBS MBC SBS 방송 다시보기", RiskGrade.LOW),
        s("netflix.com", "넷플릭스 대한민국", "영화와 시리즈 온라인 시청", RiskGrade.LOW),
        s("coupangplay.com", "쿠팡플레이", "와우회원이면 추가 요금 없이 시청", RiskGrade.LOW),
        s("kbs.co.kr", "KBS 다시보기 - my K", "KBS 프로그램 다시보기 서비스", RiskGrade.LOW),
        s("namu.wiki", "드라마 (분류) - 나무위키", "한국 드라마 목록과 방영 정보", RiskGrade.LOW),
        s("blog.naver.com", "드라마 볼 수 있는 곳 정리 : 네이버 블로그", "정식 OTT 요금제를 비교했습니다", RiskGrade.LOW),
        s("cafe.naver.com", "드라마 정보 공유 카페", "방영 정보와 시청 후기를 나눕니다", RiskGrade.LOW),
        s("tv.naver.com", "네이버 TV", "방송 클립과 하이라이트", RiskGrade.LOW),

        // ── 정상이지만 덜 알려진 곳 (규칙이 모른다 → 판별기 몫) ──
        s("kocowa.com", "KOCOWA+ 한국 드라마 정식 스트리밍", "지상파 3사가 만든 정식 서비스", RiskGrade.LOW),
        s("watcha.com", "왓챠 WATCHA", "취향 기반 영화·드라마 스트리밍", RiskGrade.LOW),
        s("onnada.com", "온나다 - TV 편성표", "지상파 케이블 편성표와 재방송 시간", RiskGrade.LOW),
        s("ohou.se", "오늘의집 - 인테리어 쇼핑", "가구 소품 인테리어 시공까지", RiskGrade.LOW),
        s("seezn.com", "시즌 Seezn", "실시간 채널과 VOD 서비스", RiskGrade.LOW),
        s("laftel.net", "라프텔 - 애니메이션 스트리밍", "정식 라이선스 애니메이션", RiskGrade.LOW),

        // ── 불법 다시보기 ──
        s("tvhot2.com", "누누티비 시즌2 - 드라마 무료 다시보기", "전편 무료 시청, 회원가입 없이", RiskGrade.HIGH),
        s("tvmon77.xyz", "티비몬 - 무료 드라마 다시보기 링크모음", "실시간 무료 시청, 고화질", RiskGrade.HIGH),
        s("newtoki-play.top", "뉴토키 다시보기 - 웹툰 드라마 무료", "무료보기 링크모음 매일 업데이트", RiskGrade.HIGH),
        s("dasibogi-link.click", "다시보기 링크모음 - 무료영화 순위", "먹튀 없는 무료 시청 추천, 토토 이벤트", RiskGrade.HIGH),

        // ── 도박·성인 ──
        s("sportstoto-live.top", "실시간 스포츠 중계 무료 - 첫충 이벤트", "꽁머니 지급, 안전 놀이터", RiskGrade.HIGH),
        s("avsee-tv.cc", "AVSEE TV 성인 영상 무료", "회원가입 없이 무료 시청", RiskGrade.HIGH),

        // ── 사칭·악성앱 ──
        s("netflix-free.xyz", "넷플릭스 무료 시청 - 회원가입 없이", "넷플릭스 콘텐츠 전편 무료", RiskGrade.HIGH),
        s("tving-vip.shop", "티빙 VIP 무료 계정 공유", "티빙 프리미엄 무료로 이용하는 방법", RiskGrade.HIGH),
        s("kakao-event.top", "카카오 경품 당첨 확인 - 본인인증", "축하합니다! 당첨자 확인", RiskGrade.HIGH),
        s("free-drama-apk.top", "드라마 무료 앱 다운로드", "안드로이드 다시보기 apk 다운로드", RiskGrade.HIGH),
        s("175.45.176.12", "무료영화 다시보기", "최신 영화 무료 스트리밍", RiskGrade.HIGH),

        // ── 경계: 정상 플랫폼인데 불법 사이트를 안내하는 글 ──
        s("blog.tistory.com", "무료 다시보기 사이트 순위 TOP10", "누누티비 대체 사이트 링크 모음", RiskGrade.MEDIUM)
    )

    private data class Sample(val result: SerpResult, val expected: RiskGrade)

    private fun s(host: String, title: String, snippet: String, expected: RiskGrade) =
        Sample(SerpResult(host, title, snippet), expected)

    @Test
    fun `규칙이 확정한 판정 중 틀린 것이 없다`() {
        val wrong = mutableListOf<String>()

        for ((result, expected) in samples) {
            val verdict = SerpRules.resolve(signals.of(result)) ?: continue
            // 경계(중)는 규칙이 확정해서는 안 되는 영역이다. 확정했다면 그것도 오답으로 본다.
            if (verdict.grade != expected) {
                wrong += "${result.host}: 정답=${expected.grade} 규칙=${verdict.grade.grade} (${verdict.reason})"
            }
        }

        assertEquals("규칙이 확정한 판정은 판별기가 고쳐줄 수 없다\n" + wrong.joinToString("\n"),
            emptyList<String>(), wrong)
    }

    @Test
    fun `규칙만으로 끝나는 비율이 유지된다`() {
        val resolved = samples.count { SerpRules.resolve(signals.of(it.result)) != null }

        // 규칙이 확정한 만큼은 판별기가 보지 않는다. 다만 호출은 항목당이 아니라
        // 화면당 1회이므로, 이 수치가 곧바로 비용은 아니다 — 한 화면이 통째로
        // 규칙에서 끝날 때만 호출이 0이 된다.
        println("규칙 확정 $resolved/${samples.size} · 판별기 ${samples.size - resolved}건")
        assertTrue(
            "규칙 확정이 $resolved/${samples.size}로 떨어졌다",
            resolved >= 14
        )
    }

    @Test
    fun `위험한 표본은 규칙이든 판별기든 반드시 누군가는 본다`() {
        // 규칙이 놓친 위험 표본은 전부 판별기로 내려가야 한다. 어느 쪽도 안 보는
        // 사각지대가 생기면 그 사이트에는 아무 표시도 뜨지 않는다.
        val blind = samples.filter { it.expected == RiskGrade.HIGH }
            .filter { sample ->
                val verdict = SerpRules.resolve(signals.of(sample.result))
                verdict != null && verdict.grade == RiskGrade.LOW
            }
        assertTrue("규칙이 '안전'으로 확정해 판별기까지 못 가는 위험 표본: $blind", blind.isEmpty())
    }
}

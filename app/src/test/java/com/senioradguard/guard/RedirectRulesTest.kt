package com.senioradguard.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 광고를 눌러 쇼핑몰로 끌려간 것을 알아채는 규칙.
 *
 * 여기서 오탐이 나면 정상 쇼핑을 방해한다 — 어르신이 스스로 쿠팡을 켰는데
 * "광고를 통해 이동했습니다"가 뜨면 앱을 못 믿게 된다.
 */
class RedirectRulesTest {

    @Test
    fun `쇼핑 앱 패키지를 알아본다`() {
        assertTrue(RedirectRules.isRedirectPackage("com.coupang.mobile"))
        assertTrue(RedirectRules.isRedirectPackage("com.einnovation.temu"))
    }

    @Test
    fun `대상이 아닌 앱은 지나친다`() {
        assertFalse(RedirectRules.isRedirectPackage("com.android.chrome"))
        assertFalse(RedirectRules.isRedirectPackage(null))
    }

    @Test
    fun `하위 도메인도 같은 쇼핑몰로 본다`() {
        assertTrue(RedirectRules.isRedirectHost("coupang.com"))
        assertTrue(RedirectRules.isRedirectHost("m.coupang.com"))
        assertTrue(RedirectRules.isRedirectHost("www.temu.com"))
    }

    // "coupang.com"을 등록했다고 "notcoupang.com"까지 잡으면 엉뚱한 사이트에
    // 경고가 뜬다. 점 단위로 끊어야 한다.
    @Test
    fun `이름이 겹치는 다른 도메인은 잡지 않는다`() {
        assertFalse(RedirectRules.isRedirectHost("notcoupang.com"))
        assertFalse(RedirectRules.isRedirectHost("coupang.com.evil.net"))
        assertFalse(RedirectRules.isRedirectHost("naver.com"))
        assertFalse(RedirectRules.isRedirectHost(null))
    }

    // 어르신에게 "com.einnovation.temu로 이동했습니다"라고 하면 알아들을 수 없다.
    @Test
    fun `사람이 읽을 이름으로 바꾼다`() {
        assertEquals("쿠팡", RedirectRules.displayName("com.coupang.mobile"))
        assertEquals("쿠팡", RedirectRules.displayName("m.coupang.com"))
        assertEquals("테무", RedirectRules.displayName("com.einnovation.temu"))
    }

    @Test
    fun `모르는 값은 그대로 돌려준다`() {
        assertEquals("example.com", RedirectRules.displayName("example.com"))
    }
}

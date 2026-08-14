package com.senioradguard.remote

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteCodeTest {

    @Test
    fun `항상 6자리 숫자를 만든다`() {
        repeat(200) {
            val code = InviteCode.generate()
            assertEquals(6, code.length)
            assertTrue("숫자만 있어야 한다: $code", code.all { c -> c.isDigit() })
        }
    }

    // 0으로 시작하는 코드가 나올 수 있어야 한다. Int로 만들어 문자열로 바꾸면
    // 앞자리 0이 사라져 5자리가 되고, 어르신이 못 넣는 코드가 된다.
    @Test
    fun `앞자리가 0이어도 6자리를 유지한다`() {
        val code = InviteCode.generate(Random(0).let { _ -> ZeroRandom() })
        assertEquals("000000", code)
    }

    @Test
    fun `공백과 하이픈을 걷어낸다`() {
        assertEquals("123456", InviteCode.normalize(" 123-456 "))
        assertEquals("123456", InviteCode.normalize("123 456"))
    }

    @Test
    fun `자릿수가 맞아야 유효하다`() {
        assertTrue(InviteCode.isValid("012345"))
        assertFalse("5자리", InviteCode.isValid("12345"))
        assertFalse("7자리", InviteCode.isValid("1234567"))
        assertFalse("숫자가 아님", InviteCode.isValid("12345a"))
        assertFalse(InviteCode.isValid(""))
    }

    private class ZeroRandom : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(until: Int) = 0
    }
}

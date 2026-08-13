package com.guradian.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStatusTest {

    private val ours = "com.guradian/com.guradian.service.GuardianAccessibilityService"

    @Test
    fun `목록에 우리 서비스만 있으면 켜짐`() {
        assertTrue(ServiceStatus.containsService(ours, ours))
    }

    @Test
    fun `다른 서비스와 함께 있어도 켜짐`() {
        val setting = "com.other.app/com.other.app.SomeService:$ours:com.third/.Svc"
        assertTrue(ServiceStatus.containsService(setting, ours))
    }

    @Test
    fun `목록에 없으면 꺼짐`() {
        assertFalse(ServiceStatus.containsService("com.other.app/.SomeService", ours))
    }

    // 강제 종료·재설치 후 안드로이드가 설정값을 통째로 지운다
    @Test
    fun `설정값이 비었거나 null이면 꺼짐`() {
        assertFalse(ServiceStatus.containsService(null, ours))
        assertFalse(ServiceStatus.containsService("", ours))
    }

    // 단순 contains로 판정하면 여기서 오판한다
    @Test
    fun `이름이 부분 문자열로 겹치는 다른 앱은 켜짐으로 보지 않음`() {
        val impostor = "com.guradian.fake/com.guradian.service.GuardianAccessibilityServiceExtra"
        assertFalse(ServiceStatus.containsService(impostor, ours))
    }

    @Test
    fun `항목 앞뒤 공백은 무시한다`() {
        assertTrue(ServiceStatus.containsService(" $ours ", ours))
    }
}

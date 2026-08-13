package com.guradian.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 네트워크를 타지 않는 경로만 검증한다. 실제 판별 품질은 표본을 직접 돌려 확인했고
 * (12/12), 여기서 재현하려면 응답을 흉내 내야 해서 오히려 실제와 멀어진다.
 */
class GeminiClassifierTest {

    @Test
    fun `키가 없으면 호출하지 않고 null을 준다`() = runTest {
        // 키 없이 빌드한 팀원의 기기에서도 앱이 죽지 않아야 한다
        val classifier = GeminiClassifier(apiKey = "")

        assertNull(classifier.classify("무료 설치 이벤트", "hankyung.com"))
    }

    @Test
    fun `빈 텍스트는 호출하지 않는다`() = runTest {
        val classifier = GeminiClassifier(apiKey = "key")

        assertNull(classifier.classify("   ", "hankyung.com"))
    }

    @Test
    fun `판정 출처에 모델명이 들어간다`() {
        // AdVerdict.source에 그대로 저장돼, 나중에 어떤 모델이 낸 판정인지 추적한다
        val classifier = GeminiClassifier(apiKey = "key", model = "gemini-3.1-flash-lite")

        assertEquals("GEMINI:gemini-3.1-flash-lite", classifier.source)
    }
}

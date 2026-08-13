package com.guradian

import android.app.Application

/**
 * Application.
 *
 * 지금은 초기화할 전역 상태가 없다. 남겨두는 이유는 task 4의 [com.guradian.store.DetectionLog]
 * 구현체가 붙을 자리가 여기이기 때문이다 — 접근성 서비스도 같은 프로세스라
 * 여기서 한 번 만들어두면 그대로 쓴다.
 */
class GuardianApp : Application()

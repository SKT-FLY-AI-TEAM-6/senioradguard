# Phase 1: 서비스 통합 + DB 정리 + Layer 1/3 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 팀원의 `AdDetectService`(공식 광고 라벨 감지)를 SeniorAdGuard에 이식해 단일 `AccessibilityService`로 통합하고, 그 과정에서 기존 Room DB의 성능·정합성 버그 3건을 함께 고친다.

**Architecture:** `AdGuardAccessibilityService` 하나가 이벤트를 받아 루트 노드를 한 번만 가져오고, Layer 1(공식 라벨 → 비차단 테두리)과 Layer 3(설치 유도 → 차단 팝업)에 배분한다. Layer 2(LLM)는 Phase 2에서 이 구조 위에 얹는다.

**Tech Stack:** Kotlin, AccessibilityService API, Room 2.7.1, WorkManager, JUnit 4

## Global Constraints

- minSdk 26 / targetSdk 36 / compileSdk 36 — 이보다 높은 API를 쓸 때는 반드시 `Build.VERSION.SDK_INT` 분기
- 팀원 코드(`collectAdRegions` / `containerOf` / `adLinkOf` / `isAdLabel` / `isAdContainer` / `adContainerIds`)의 **로직과 상수는 변경 금지**. 허용되는 변경은 패키지명과 `Context` 주입뿐
- Layer 1·2 오버레이는 `TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_NOT_TOUCHABLE`을 반드시 유지 (구글 정책 — 광고 클릭·구매·설치를 방해하면 안 됨)
- 팀원 코드 원본 위치: `C:\Users\skdla\Downloads\AdDetectService.kt` (패키지 `com.flyai.adalert`)
- 새 파일 패키지 루트: `com.senioradguard`
- 테스트 실행: 단위 `./gradlew testDebugUnitTest`, 계측 `./gradlew connectedDebugAndroidTest`
- **모든 실동작 검증은 USB로 연결한 실제 안드로이드 기기에서 한다.** 에뮬레이터는 쓰지 않는다 (사유는 아래 "실기기 준비" 참고)
- Room 2.7.1에서 인자 없는 `fallbackToDestructiveMigration()`은 deprecated — `fallbackToDestructiveMigration(dropAllTables = true)`를 쓸 것
- 커밋 메시지는 한국어 본문, 마지막 두 줄에 다음을 붙일 것:
  ```
  Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6
  ```

## 실기기 준비 (Task 3 이전에 1회)

에뮬레이터에서는 이 앱의 핵심 감지 대상을 재현할 수 없다. 실제로 지난 검증(2026-08-10)에서 인스타그램 미설치·유튜브 광고 미재현 때문에 앱 자신을 임시 감지 대상에 넣어 우회 검증해야 했다. 실기기에서는 **진짜 유튜브 인스트림 광고, 인스타그램 Sponsored 피드, 당근 광고**로 검증할 수 있다. 제조사 배터리 최적화로 인한 서비스 강제 종료도 실기기에서만 드러난다.

- [ ] 기기에서 **개발자 옵션 → USB 디버깅** 활성화
- [ ] USB 연결 후 기기 화면의 "USB 디버깅을 허용하시겠습니까?" 승인
- [ ] 연결 확인:
  ```bash
  adb devices -l
  ```
  Expected: 기기 한 대가 `device` 상태로 표시 (`unauthorized`나 `offline`이면 케이블 재연결 후 승인)
- [ ] 검증 대상 앱 설치 확인 — 유튜브, 인스타그램, 크롬. 없으면 Play Store에서 설치
- [ ] 삼성 기기인 경우 **설정 → 배터리 → 백그라운드 사용 제한**에서 SeniorAdGuard를 제외 등록 (제조사 최적화가 AccessibilityService를 강제 종료할 수 있음)

**반복 검증 시 반드시 지킬 순서**: 재설치나 `am force-stop`을 하면 Android가 안전장치로 `enabled_accessibility_services`를 자동으로 지운다. 항상 **설치/force-stop → 접근성 서비스 재활성화 → 앱 실행** 순서로 할 것. 반대로 하면 설정에서 "활성화됨"으로 보여도 실제로 이벤트를 받지 못한다.

- [ ] 아래 스크립트를 `scripts/redeploy.sh`로 저장하고 `chmod +x scripts/redeploy.sh`. Task 7·8의 검증 단계가 이 스크립트를 호출한다:

```bash
#!/usr/bin/env bash
set -e
SERVICE="com.senioradguard/com.senioradguard.service.AdGuardAccessibilityService"
./gradlew installDebug
adb shell settings put secure enabled_accessibility_services "$SERVICE"
adb shell settings put secure accessibility_enabled 1
adb shell appops set com.senioradguard SYSTEM_ALERT_WINDOW allow
adb shell am start -n com.senioradguard/.MainActivity
echo "재배포 완료 — 접근성 서비스 활성화됨"
```

## 파일 구조

| 파일 | 책임 |
|---|---|
| `detector/DomainMatcher.kt` (신규) | 도메인 접미사 분해 매칭 — 순수 함수 |
| `detector/BlacklistCache.kt` (신규) | 블랙리스트 메모리 캐시 + 무효화. 프로세스 전역 |
| `detector/db/AdVerdict.kt` (신규) | Layer 2 판정 캐시 엔티티 |
| `detector/db/AdVerdictDao.kt` (신규) | 판정 캐시 DAO |
| `region/AdLabelRules.kt` (신규) | 광고 라벨/컨테이너 판정 — 순수 함수, 팀원 코드 이식 |
| `region/AdRegionScanner.kt` (신규) | 노드 트리 순회 → 광고 영역 목록. 팀원 코드 이식 |
| `overlay/AdBorderOverlay.kt` (신규) | 비차단 테두리 오버레이 + 소리/진동. 팀원 코드 이식 |
| `guard/InstallGuard.kt` (신규) | 설치 유도 감지 + 차단 경고 + 카카오 알림 |
| `service/AdGuardAccessibilityService.kt` (수정) | 단일 진입점. 레이어 배분만 담당 |

## 테스트 전략에 대한 사전 고지

`AdLabelRules`, `DomainMatcher`, `BlacklistCache`는 안드로이드 의존이 없어 JVM 단위 테스트로 덮는다.

`AdRegionScanner.collectAdRegions`는 `AccessibilityNodeInfo` 트리를 순회한다. 이 클래스는 시스템이 생성하는 객체라 단위 테스트에서 트리를 조립할 방법이 마땅치 않고(Mockito로 mock하면 `Rect`가 스텁이라 좌표 로직이 무의미해지고, Robolectric은 새 의존성), 계측 테스트에서도 임의의 노드 트리를 만들 수 없다. **이 부분은 verbatim 이식이므로 실기기 수동 검증으로 확인한다.** 검증 절차는 Task 7에 있다. 이는 의도된 선택이며, 팀원 코드가 실기기에서 이미 검증된 상태라는 점에 근거한다.

---

### Task 1: 블랙리스트 도메인 매칭을 O(1)로 전환

현재 `AdDetector.scoreByBlacklist()`는 14만 개 도메인을 `endsWith`로 선형 탐색한다. 성능 문제이면서 **오탐 버그**이기도 하다 — `"notdoubleclick.net".endsWith("doubleclick.net")`이 `true`라 무관한 도메인을 광고로 판정한다. 접미사 분해 + 정확 일치로 둘 다 해결한다.

**Files:**
- Create: `app/src/main/java/com/senioradguard/detector/DomainMatcher.kt`
- Create: `app/src/test/java/com/senioradguard/detector/DomainMatcherTest.kt`
- Modify: `app/src/main/java/com/senioradguard/detector/AdDetector.kt` (96-106행 `scoreByBlacklist`)

**Interfaces:**
- Consumes: 없음
- Produces: `object DomainMatcher { fun isBlocked(host: String, blocked: Set<String>): Boolean }`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/java/com/senioradguard/detector/DomainMatcherTest.kt`:

```kotlin
package com.senioradguard.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private val blocked = setOf("doubleclick.net", "googlesyndication.com", "co.kr")

    @Test
    fun `정확히 일치하면 차단`() {
        assertTrue(DomainMatcher.isBlocked("doubleclick.net", blocked))
    }

    @Test
    fun `서브도메인도 차단`() {
        assertTrue(DomainMatcher.isBlocked("ads.g.doubleclick.net", blocked))
    }

    @Test
    fun `목록에 없으면 통과`() {
        assertFalse(DomainMatcher.isBlocked("example.com", blocked))
    }

    // 기존 endsWith 구현의 오탐 버그 — 라벨 경계를 무시해 통과시켰다
    @Test
    fun `라벨 중간에서 끝나는 문자열은 차단하지 않음`() {
        assertFalse(DomainMatcher.isBlocked("notdoubleclick.net", blocked))
        assertFalse(DomainMatcher.isBlocked("evil-googlesyndication.com", blocked))
    }

    @Test
    fun `대소문자 무시`() {
        assertTrue(DomainMatcher.isBlocked("ADS.DoubleClick.NET", blocked))
    }

    @Test
    fun `끝에 붙은 루트 점도 처리`() {
        assertTrue(DomainMatcher.isBlocked("doubleclick.net.", blocked))
    }

    @Test
    fun `빈 문자열은 통과`() {
        assertFalse(DomainMatcher.isBlocked("", blocked))
    }
}
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew testDebugUnitTest --tests "com.senioradguard.detector.DomainMatcherTest"`
Expected: FAIL — `Unresolved reference: DomainMatcher` (컴파일 에러)

- [ ] **Step 3: 최소 구현 작성**

`app/src/main/java/com/senioradguard/detector/DomainMatcher.kt`:

```kotlin
package com.senioradguard.detector

/**
 * 호스트명이 차단 목록에 해당하는지 판정한다.
 *
 * 목록이 14만 건 규모라 endsWith 선형 탐색은 쓸 수 없다. 호스트를 라벨 단위로
 * 뒤에서부터 잘라 올라가며 HashSet 정확 일치로 조회하면 최대 라벨 수(보통 4~5)
 * 번만에 끝난다.
 *
 * 라벨 경계에서만 자르므로 "notdoubleclick.net"이 "doubleclick.net"에 걸리는
 * endsWith 방식의 오탐도 함께 사라진다.
 */
object DomainMatcher {

    fun isBlocked(host: String, blocked: Set<String>): Boolean {
        if (host.isEmpty() || blocked.isEmpty()) return false

        val normalized = host.lowercase().trimEnd('.')
        if (normalized.isEmpty()) return false

        var index = 0
        while (index >= 0 && index < normalized.length) {
            if (normalized.substring(index) in blocked) return true
            val next = normalized.indexOf('.', index)
            index = if (next < 0) -1 else next + 1
        }
        return false
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew testDebugUnitTest --tests "com.senioradguard.detector.DomainMatcherTest"`
Expected: PASS (7개 테스트)

- [ ] **Step 5: `AdDetector`가 새 매처를 쓰도록 교체**

`AdDetector.kt`의 `scoreByBlacklist`(96-106행)를 다음으로 교체:

```kotlin
    suspend fun scoreByBlacklist(urls: List<String>): Float {
        if (urls.isEmpty()) return 0f
        val domains = getCachedDomains()
        for (url in urls) {
            val domain = extractDomain(url) ?: continue
            if (DomainMatcher.isBlocked(domain, domains)) return 1.0f
        }
        return 0f
    }
```

- [ ] **Step 6: 컴파일 확인**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add app/src/main/java/com/senioradguard/detector/DomainMatcher.kt \
        app/src/test/java/com/senioradguard/detector/DomainMatcherTest.kt \
        app/src/main/java/com/senioradguard/detector/AdDetector.kt
git commit -m "perf: 블랙리스트 도메인 매칭을 접미사 분해 O(1)로 전환

14만 건 endsWith 선형 탐색을 라벨 단위 접미사 분해 + HashSet 조회로 교체.
URL당 최대 14만 회 비교가 4~5회로 줄고, 'notdoubleclick.net'이
'doubleclick.net'에 걸리던 라벨 경계 오탐도 함께 해결됨.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6"
```

---

### Task 2: 블랙리스트 갱신 시 메모리 캐시 무효화

`BlacklistUpdateWorker`는 주 1회 DB를 갱신하지만, 실행 중인 `AdDetector` 인스턴스의 메모리 캐시(`blacklistedDomainsCache`)는 그대로 남는다. 캐시를 비우는 `AdDetector.updateBlacklist()`는 **호출하는 곳이 없는 dead code**다. 캐시를 프로세스 전역 객체로 빼고 워커가 무효화하도록 연결한다.

**Files:**
- Create: `app/src/main/java/com/senioradguard/detector/BlacklistCache.kt`
- Create: `app/src/test/java/com/senioradguard/detector/BlacklistCacheTest.kt`
- Modify: `app/src/main/java/com/senioradguard/detector/AdDetector.kt` (47-48행 캐시 필드, 108-119행 `getCachedDomains`, 148-162행 `updateBlacklist` 삭제)
- Modify: `app/src/main/java/com/senioradguard/detector/BlacklistUpdateWorker.kt` (22-25행 `doWork`)
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Interfaces:**
- Consumes: `DomainMatcher` (Task 1)
- Produces:
  - `object BlacklistCache`
  - `suspend fun BlacklistCache.domains(loader: suspend () -> Set<String>): Set<String>`
  - `fun BlacklistCache.invalidate()`

- [ ] **Step 1: 코루틴 테스트 의존성 추가**

`gradle/libs.versions.toml`의 `[versions]`에 추가:

```toml
coroutines = "1.11.0"
```

`[libraries]`에 추가:

```toml
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

`app/build.gradle.kts`의 `dependencies` 블록에 추가:

```kotlin
    testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: 실패하는 테스트 작성**

`app/src/test/java/com/senioradguard/detector/BlacklistCacheTest.kt`:

```kotlin
package com.senioradguard.detector

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BlacklistCacheTest {

    private val loadCount = AtomicInteger(0)

    private suspend fun loader(): Set<String> {
        loadCount.incrementAndGet()
        return setOf("doubleclick.net")
    }

    @Before
    fun reset() {
        BlacklistCache.invalidate()
        loadCount.set(0)
    }

    @Test
    fun `첫 조회는 로더를 호출한다`() = runTest {
        val result = BlacklistCache.domains(::loader)
        assertEquals(setOf("doubleclick.net"), result)
        assertEquals(1, loadCount.get())
    }

    @Test
    fun `두 번째 조회는 캐시를 쓴다`() = runTest {
        BlacklistCache.domains(::loader)
        BlacklistCache.domains(::loader)
        assertEquals(1, loadCount.get())
    }

    @Test
    fun `invalidate 후에는 다시 로드한다`() = runTest {
        BlacklistCache.domains(::loader)
        BlacklistCache.invalidate()
        BlacklistCache.domains(::loader)
        assertEquals(2, loadCount.get())
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./gradlew testDebugUnitTest --tests "com.senioradguard.detector.BlacklistCacheTest"`
Expected: FAIL — `Unresolved reference: BlacklistCache`

- [ ] **Step 4: 최소 구현 작성**

`app/src/main/java/com/senioradguard/detector/BlacklistCache.kt`:

```kotlin
package com.senioradguard.detector

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 블랙리스트 도메인 집합의 프로세스 전역 메모리 캐시.
 *
 * AccessibilityService 인스턴스와 WorkManager 워커는 서로 다른 컴포넌트라
 * 인스턴스 필드로 캐시를 들고 있으면 워커의 갱신이 서비스에 반영되지 않는다.
 * 캐시를 전역으로 빼고 워커가 invalidate()를 호출하게 한다.
 */
object BlacklistCache {

    private val mutex = Mutex()

    @Volatile
    private var cached: Set<String>? = null

    suspend fun domains(loader: suspend () -> Set<String>): Set<String> {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: loader().also { cached = it }
        }
    }

    /** 원격 갱신이 성공했을 때 호출한다. 다음 조회에서 DB를 다시 읽는다. */
    fun invalidate() {
        cached = null
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew testDebugUnitTest --tests "com.senioradguard.detector.BlacklistCacheTest"`
Expected: PASS (3개 테스트)

- [ ] **Step 6: `AdDetector`를 새 캐시로 교체**

`AdDetector.kt`에서 다음을 삭제:
- 47행 `private val blacklistCacheMutex = Mutex()`
- 48행 `private var blacklistedDomainsCache: Set<String>? = null`
- 108-119행 `getCachedDomains()` 전체
- 148-162행 `updateBlacklist()` 전체 (dead code)
- import 2줄: `import kotlinx.coroutines.sync.Mutex`, `import kotlinx.coroutines.sync.withLock`

`scoreByBlacklist` 안의 `getCachedDomains()` 호출을 다음으로 교체:

```kotlin
        val domains = BlacklistCache.domains { blacklistRepository.getDomains() }
```

- [ ] **Step 7: 워커가 갱신 후 무효화하도록 연결**

`BlacklistUpdateWorker.kt`의 `doWork()`를 다음으로 교체:

```kotlin
    override suspend fun doWork(): Result {
        val repository = BlacklistRepository(applicationContext)
        val updated = repository.refreshFromRemote(BLACKLIST_SOURCES)
        if (updated) BlacklistCache.invalidate()
        return if (updated) Result.success() else Result.retry()
    }
```

- [ ] **Step 8: 전체 단위 테스트 + 컴파일 확인**

Run: `./gradlew testDebugUnitTest compileDebugKotlin`
Expected: BUILD SUCCESSFUL, 실패 테스트 0건

- [ ] **Step 9: 커밋**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/com/senioradguard/detector/BlacklistCache.kt \
        app/src/test/java/com/senioradguard/detector/BlacklistCacheTest.kt \
        app/src/main/java/com/senioradguard/detector/AdDetector.kt \
        app/src/main/java/com/senioradguard/detector/BlacklistUpdateWorker.kt
git commit -m "fix: 블랙리스트 원격 갱신이 실행 중 프로세스에 반영되도록 수정

메모리 캐시를 AdDetector 인스턴스 필드에서 프로세스 전역 BlacklistCache로
분리하고, BlacklistUpdateWorker가 갱신 성공 시 invalidate()를 호출하도록 연결.
기존에는 주 1회 갱신분이 프로세스 재시작 전까지 반영되지 않았음.
호출부가 없던 AdDetector.updateBlacklist() dead code 제거.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6"
```

---

### Task 3: `AdVerdict` 판정 캐시 테이블 추가 + 파괴적 마이그레이션

Phase 2가 쓸 판정 캐시 테이블을 미리 만든다. **`@Database(version = 1)`에 마이그레이션 경로가 없으므로 엔티티를 추가하고 version 2로 올리면 기존 설치 기기가 `IllegalStateException`으로 크래시한다.** `fallbackToDestructiveMigration(dropAllTables = true)`을 함께 넣는다. 블랙리스트는 워커가 다시 받아오고 판정은 어차피 캐시라 데이터 유실이 무해하다.

**Files:**
- Create: `app/src/main/java/com/senioradguard/detector/db/AdVerdict.kt`
- Create: `app/src/main/java/com/senioradguard/detector/db/AdVerdictDao.kt`
- Create: `app/src/androidTest/java/com/senioradguard/detector/db/AdVerdictDaoTest.kt`
- Modify: `app/src/main/java/com/senioradguard/detector/db/AppDatabase.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `data class AdVerdict(key: String, isAd: Boolean, confidence: Float, source: String, updatedAt: Long)`
  - `interface AdVerdictDao` — `suspend fun find(key: String, notBefore: Long): AdVerdict?`, `suspend fun upsert(verdict: AdVerdict)`, `suspend fun deleteExpired(notBefore: Long): Int`
  - `AppDatabase.adVerdictDao(): AdVerdictDao`

- [ ] **Step 1: room-testing 의존성 추가**

`gradle/libs.versions.toml`의 `[libraries]`에 추가:

```toml
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

`app/build.gradle.kts`의 `dependencies` 블록에 추가:

```kotlin
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: 실패하는 계측 테스트 작성**

`app/src/androidTest/java/com/senioradguard/detector/db/AdVerdictDaoTest.kt`:

```kotlin
package com.senioradguard.detector.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdVerdictDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AdVerdictDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = db.adVerdictDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun 저장한_판정을_다시_읽는다() = runTest {
        val now = 1_000_000L
        dao.upsert(AdVerdict("naver.com|abc", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = now))

        val found = dao.find("naver.com|abc", notBefore = now - 1)

        assertNotNull(found)
        assertEquals(true, found!!.isAd)
        assertEquals(0.9f, found.confidence, 0.001f)
        assertEquals("LLM", found.source)
    }

    @Test
    fun 부정_판정도_저장된다() = runTest {
        val now = 1_000_000L
        dao.upsert(AdVerdict("naver.com|def", isAd = false, confidence = 0.1f, source = "LLM", updatedAt = now))

        val found = dao.find("naver.com|def", notBefore = now - 1)

        assertNotNull(found)
        assertEquals(false, found!!.isAd)
    }

    @Test
    fun 같은_키로_저장하면_덮어쓴다() = runTest {
        dao.upsert(AdVerdict("k", isAd = false, confidence = 0.1f, source = "LLM", updatedAt = 100L))
        dao.upsert(AdVerdict("k", isAd = true, confidence = 0.8f, source = "VISION_LLM", updatedAt = 200L))

        val found = dao.find("k", notBefore = 0L)

        assertEquals(true, found!!.isAd)
        assertEquals("VISION_LLM", found.source)
    }

    @Test
    fun TTL이_지난_판정은_조회되지_않는다() = runTest {
        dao.upsert(AdVerdict("old", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = 100L))

        assertNull(dao.find("old", notBefore = 500L))
    }

    @Test
    fun 없는_키는_null() = runTest {
        assertNull(dao.find("missing", notBefore = 0L))
    }

    @Test
    fun deleteExpired는_만료된_행만_지운다() = runTest {
        dao.upsert(AdVerdict("old", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = 100L))
        dao.upsert(AdVerdict("new", isAd = true, confidence = 0.9f, source = "LLM", updatedAt = 900L))

        val deleted = dao.deleteExpired(notBefore = 500L)

        assertEquals(1, deleted)
        assertNull(dao.find("old", notBefore = 0L))
        assertNotNull(dao.find("new", notBefore = 0L))
    }

    @Test
    fun 블랙리스트_DAO도_계속_동작한다() = runTest {
        db.blacklistDao().insertAll(listOf(BlacklistDomain("doubleclick.net", 1L)))
        assertEquals(listOf("doubleclick.net"), db.blacklistDao().getAllDomains())
    }
}
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

USB 기기 연결 상태에서:

```bash
adb devices -l          # device 상태 확인
./gradlew connectedDebugAndroidTest --tests "com.senioradguard.detector.db.AdVerdictDaoTest"
```

Expected: FAIL — `Unresolved reference: AdVerdict` (컴파일 에러)

- [ ] **Step 4: 엔티티 작성**

`app/src/main/java/com/senioradguard/detector/db/AdVerdict.kt`:

```kotlin
package com.senioradguard.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Layer 2(LLM) 광고 판정 캐시 1건.
 *
 * key는 "$sourceKey|$textHash" 형식이다. sourceKey는 브라우저면 도메인,
 * 앱이면 패키지명. textHash는 카드 텍스트를 정규화해 해싱한 값.
 *
 * isAd=false(광고 아님)도 저장한다. 화면의 카드 대부분은 광고가 아니므로
 * 부정 판정 캐시에서 LLM 호출 절감의 대부분이 나온다.
 */
@Entity(tableName = "ad_verdict")
data class AdVerdict(
    @PrimaryKey val key: String,
    val isAd: Boolean,
    val confidence: Float,
    /** LLM / VISION_LLM / VIEWID */
    val source: String,
    val updatedAt: Long
)
```

- [ ] **Step 5: DAO 작성**

`app/src/main/java/com/senioradguard/detector/db/AdVerdictDao.kt`:

```kotlin
package com.senioradguard.detector.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AdVerdictDao {

    /** notBefore보다 오래된 판정은 만료로 보고 반환하지 않는다. */
    @Query("SELECT * FROM ad_verdict WHERE `key` = :key AND updatedAt >= :notBefore")
    suspend fun find(key: String, notBefore: Long): AdVerdict?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(verdict: AdVerdict)

    /** 만료 행 정리. 주 1회 BlacklistUpdateWorker에서 호출한다. */
    @Query("DELETE FROM ad_verdict WHERE updatedAt < :notBefore")
    suspend fun deleteExpired(notBefore: Long): Int
}
```

- [ ] **Step 6: `AppDatabase`를 version 2로 올리고 파괴적 마이그레이션 추가**

`app/src/main/java/com/senioradguard/detector/db/AppDatabase.kt` 전체를 다음으로 교체:

```kotlin
package com.senioradguard.detector.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BlacklistDomain::class, AdVerdict::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blacklistDao(): BlacklistDao

    abstract fun adVerdictDao(): AdVerdictDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "senior_ad_guard.db"
                )
                    // 블랙리스트는 워커가 다시 받아오고 판정은 캐시라 유실이 무해하다.
                    // 손으로 쓴 마이그레이션보다 실수 여지가 적어 파괴적 마이그레이션을 쓴다.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
```

- [ ] **Step 7: 계측 테스트 통과 확인**

Run: `./gradlew connectedDebugAndroidTest --tests "com.senioradguard.detector.db.AdVerdictDaoTest"`
Expected: PASS (7개 테스트)

- [ ] **Step 8: 실기기에서 version 1 → 2 업그레이드가 크래시하지 않는지 확인**

파괴적 마이그레이션이 실제로 동작하는지 보는 단계라 반드시 수행할 것. 이미 기기에 구버전이 깔려 있다면 1번은 건너뛰어도 된다.

```bash
# 1. version 1 상태(Task 2 시점)를 설치해 DB 파일을 만든다
git stash
./gradlew installDebug
adb shell am start -n com.senioradguard/.MainActivity
sleep 5
adb shell am force-stop com.senioradguard
git stash pop

# 2. version 2를 덮어 설치하고 크래시 없이 뜨는지 본다
./gradlew installDebug
adb logcat -c
adb shell am start -n com.senioradguard/.MainActivity
sleep 5
adb logcat -d -s AndroidRuntime:E | tail -30
```

Expected: 앱이 화면에 정상 표시되고, 로그에 `IllegalStateException: A migration from 1 to 2 was required but not found`가 **없을 것**

DB가 실제로 재생성됐는지 확인 (디버그 빌드라 `run-as` 사용 가능):

```bash
adb shell run-as com.senioradguard ls -l databases/
```

Expected: `senior_ad_guard.db` 존재

- [ ] **Step 9: 커밋**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/com/senioradguard/detector/db/ \
        app/src/androidTest/java/com/senioradguard/detector/db/AdVerdictDaoTest.kt
git commit -m "feat: Layer 2 판정 캐시 테이블 추가 + 파괴적 마이그레이션

ad_verdict 테이블(key PK, isAd, confidence, source, updatedAt)과 DAO 추가.
TTL 조회/만료 정리 지원. 부정 판정도 저장해 LLM 호출을 절감한다.

AppDatabase를 version 2로 올리면서 fallbackToDestructiveMigration을 함께
추가. 기존에는 마이그레이션 경로가 없어 엔티티 추가 시 기존 설치 기기가
IllegalStateException으로 크래시하는 상태였다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6"
```

---

### Task 4: `AdLabelRules` 이식 — 광고 라벨/컨테이너 판정

팀원 코드의 순수 함수 부분(`isAdLabel`, `isAdContainer`, `adContainerIds`)을 옮긴다. 안드로이드 의존이 없어 JVM 단위 테스트로 완전히 덮을 수 있다.

**Files:**
- Create: `app/src/main/java/com/senioradguard/region/AdLabelRules.kt`
- Create: `app/src/test/java/com/senioradguard/region/AdLabelRulesTest.kt`

**Interfaces:**
- Consumes: 없음
- Produces: `object AdLabelRules { fun isAdLabel(s: String): Boolean; fun isAdContainer(id: String?): Boolean }`

- [ ] **Step 1: 실패하는 테스트 작성**

`app/src/test/java/com/senioradguard/region/AdLabelRulesTest.kt`:

```kotlin
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
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./gradlew testDebugUnitTest --tests "com.senioradguard.region.AdLabelRulesTest"`
Expected: FAIL — `Unresolved reference: AdLabelRules`

- [ ] **Step 3: 팀원 코드를 그대로 옮긴다**

`app/src/main/java/com/senioradguard/region/AdLabelRules.kt`:

```kotlin
package com.senioradguard.region

/**
 * 공식 광고 표기 판정 규칙.
 *
 * 팀원 AdDetectService(com.flyai.adalert)에서 이식. 로직과 상수는 변경하지 않았다 —
 * 아래 처리는 모두 실제 앱에서 부딪혀 나온 대응이라 임의로 손대면 회귀한다.
 */
object AdLabelRules {

    /**
     * 모바일 웹은 라벨 없는 이미지 배너가 많은데, 크롬은 HTML의 id 속성을 노드의
     * viewIdResourceName으로 노출한다(서비스 설정의 flagReportViewIds 필요).
     * 광고 네트워크가 쓰는 id로 배너 컨테이너를 직접 찾는다.
     * 단 추천 위젯(Dable·Taboola 등)은 광고와 진짜 기사가 섞여 있어 컨테이너로 잡으면 안 되고,
     * 그 안의 개별 광고에 붙는 "AD" 라벨로만 잡는다.
     */
    private val adContainerIds = listOf(
        "div-gpt-ad", "adsbygoogle", "google_ads",   // 구글 (표준 광고 슬롯 id)
        "aceplanet", "mobondivbanner", "adfit", "clickads", "innorame", "criteo"
    )

    fun isAdContainer(id: String?): Boolean {
        val s = id?.lowercase() ?: return false
        return adContainerIds.any { it in s }
    }

    /**
     * 광고 카드 전체가 한 노드로 합쳐져 설명 문구 안에 라벨이 섞이는 경우(유튜브 Litho)가 있으므로,
     * 구분자(·, 쉼표, " - ")로 쪼갠 토큰이 정확히 광고 표기일 때만 인정한다. (제목 속 단어는 오탐 안 됨)
     * " - "는 양옆 공백이 있을 때만 구분자로 취급해 단어 속 하이픈(non-sponsored 등)은 쪼개지 않는다.
     * 웹 광고는 문구 사이에 폭 0인 문자를 끼워 넣어 차단을 피하기도 해서 먼저 제거한다.
     */
    fun isAdLabel(s: String): Boolean =
        s.lowercase().replace(Regex("[\\u200b-\\u200d\\ufeff]"), "")
            .split(Regex("\\s-\\s|[·,，•∙‧]")).any {
                it.trim() in setOf(
                    "광고", "스폰서", "sponsored", "협찬 광고", "이웃광고",
                    "ad", "advertisement"   // 모바일 웹 광고 표기
                )
            }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew testDebugUnitTest --tests "com.senioradguard.region.AdLabelRulesTest"`
Expected: PASS (10개 테스트)

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/java/com/senioradguard/region/AdLabelRules.kt \
        app/src/test/java/com/senioradguard/region/AdLabelRulesTest.kt
git commit -m "feat: 공식 광고 라벨 판정 규칙 이식 (AdLabelRules)

팀원 AdDetectService의 isAdLabel/isAdContainer/adContainerIds를 순수 함수로
분리 이식. 로직과 상수는 무변경.

단위 테스트로 다음을 고정: 구분자 토큰화(· , ' - '), 폭 0 문자 제거,
'non-sponsored'가 오탐되지 않을 것, 제목 속 단어 미검출, 대소문자 무시.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6"
```

---

### Task 5: `AdRegionScanner` 이식 — 노드 트리 순회

팀원 코드의 트리 순회 부분을 `AccessibilityService`에서 떼어내 순수 클래스로 옮긴다.

**Files:**
- Create: `app/src/main/java/com/senioradguard/region/AdRegionScanner.kt`

**Interfaces:**
- Consumes: `AdLabelRules.isAdLabel(String)`, `AdLabelRules.isAdContainer(String?)` (Task 4)
- Produces: `class AdRegionScanner { fun scan(root: AccessibilityNodeInfo): List<Rect> }`

- [ ] **Step 1: 스캐너 작성**

`app/src/main/java/com/senioradguard/region/AdRegionScanner.kt`:

```kotlin
package com.senioradguard.region

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 화면 노드 트리를 순회해 공식 광고 표기가 붙은 영역을 찾는다.
 *
 * 팀원 AdDetectService(com.flyai.adalert)에서 이식. 로직과 상수는 변경하지 않았다.
 * AccessibilityService 상속에서 분리하기 위해 inBrowser 판정만 scan() 안으로 옮겼다.
 */
class AdRegionScanner {

    /** 모바일 웹은 화면 구조가 앱과 달라 광고 영역을 다른 방식으로 잡는다 */
    private val browsers = setOf("com.android.chrome", "com.sec.android.app.sbrowser")
    private var inBrowser = false

    fun scan(root: AccessibilityNodeInfo): List<Rect> {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        inBrowser = root.packageName?.toString() in browsers
        val regions = mutableListOf<Rect>()
        collectAdRegions(root, 0, screen, regions)
        // 전체 화면 광고가 하나라도 있으면 전체 테두리 하나만 표시
        regions.firstOrNull { it == screen }?.let { regions.retainAll(listOf(it)) }
        return regions
    }

    /**
     * 노드 트리를 직접 순회해 공식 광고 표기를 찾고 광고 영역들을 모은다.
     * (유튜브의 Litho UI는 findAccessibilityNodeInfosByText를 지원하지 않아 직접 순회가 필요)
     */
    private fun collectAdRegions(
        node: AccessibilityNodeInfo,
        depth: Int,
        screen: Rect,
        out: MutableList<Rect>
    ) {
        // 인스타그램 릴스는 광고 라벨이 30단계보다 깊이 있어 여유 있게 잡는다
        if (depth > 60 || out.size >= 5) return
        // 화면 밖 요소는 릴스 페이저가 좌표를 어긋나게, 크롬이 높이 0으로 접어서 알려주므로
        // 실제로 화면에 크기를 차지할 때만 광고로 인정한다 (안 그러면 가짜 영역이 한도를 채운다)
        if (node.isVisibleToUser) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b.width() > 0 && b.height() > 0) {
                // 광고 네트워크 컨테이너는 그 노드 자체가 곧 광고 영역, 라벨은 카드를 거슬러 올라가 찾는다
                val byId = AdLabelRules.isAdContainer(node.viewIdResourceName)
                if (byId || AdLabelRules.isAdLabel("${node.text ?: ""} · ${node.contentDescription ?: ""}")) {
                    val r = if (byId) b else if (inBrowser) adLinkOf(node, screen) else containerOf(node, screen)
                    if (r != null && out.none { it.contains(r) || r.contains(it) }) out.add(r)
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectAdRegions(node.getChild(i) ?: continue, depth + 1, screen, out)
        }
    }

    /**
     * 모바일 웹의 광고는 링크라서, 라벨을 감싼 가장 가까운 클릭 가능한 조상이 광고 한 칸이다.
     * (웹 문서에는 "광고 카드"에 해당하는 구조가 없어 부모를 계속 올라가면 문서 전체를 잡는다)
     * 광고 한 칸이라기엔 너무 큰 곳까지 올라가면 포기한다 — 그런 광고는 대개 id로 따로 잡힌다.
     */
    private fun adLinkOf(marker: AccessibilityNodeInfo, screen: Rect): Rect? {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker
        while (cur != null) {
            cur.getBoundsInScreen(r)
            if (r.height() > screen.height() * 0.5) return null
            if (cur.isClickable) return Rect(r)
            cur = cur.parent
        }
        return null
    }

    /**
     * 라벨 노드에서 가장 가까운 광고 카드 컨테이너(폭 70% 이상, 높이 8~85%)를 찾는다.
     * 스크롤 피드 자체는 카드가 아니므로 피드에 닿으면 탐색을 멈추고, 그때까지 카드가 없으면
     * 라벨이 속한 피드 항목부터 피드 아래 끝까지를 광고 영역으로 본다.
     * (인스타그램은 광고 게시물의 헤더·본문이 각각 별도 피드 항목이라 카드 조상이 없음)
     * 카드를 못 찾거나 영역이 화면의 75% 이상이면 전체 화면 광고로 본다.
     */
    private fun containerOf(marker: AccessibilityNodeInfo, screen: Rect): Rect {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker   // 병합 노드는 라벨 노드 자신이 곧 광고 카드
        var item: Rect? = null                     // 직전에 지나온 조상 = 피드의 항목
        while (cur != null) {
            cur.getBoundsInScreen(r)
            if (cur.isScrollable) return item?.apply { bottom = maxOf(bottom, r.bottom) } ?: Rect(screen)
            // 화면 가장자리에 걸쳐 잘려 보이는 카드는 최소 높이 조건을 면제
            val clipped = r.top <= screen.height() * 0.06 || r.bottom >= screen.height() * 0.94
            if (r.width() >= screen.width() * 0.7 && r.height() <= screen.height() * 0.85 &&
                (r.height() >= screen.height() * 0.08 || clipped)
            ) break
            item = Rect(r)
            cur = cur.parent
        }
        val region = if (cur != null) Rect(r) else Rect(screen)
        if (region.height() >= screen.height() * 0.75) region.set(screen)
        return region
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

동작 검증은 Task 7의 실기기 수동 검증에서 한다 (사유는 이 문서 상단 "테스트 전략에 대한 사전 고지" 참고).

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/senioradguard/region/AdRegionScanner.kt
git commit -m "feat: 광고 영역 트리 순회 이식 (AdRegionScanner)

팀원 AdDetectService의 collectAdRegions/containerOf/adLinkOf를 순수 클래스로
분리 이식. 로직·상수 무변경, inBrowser 판정만 scan() 진입점으로 이동.

유튜브 Litho 병합 노드, 인스타 릴스 depth 60, 화면 밖 0높이 노드 처리 등
실기기 대응이 그대로 유지됨.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6"
```

---

### Task 6: `AdBorderOverlay` 이식 + AI 추정용 점선 스타일

팀원의 오버레이를 옮기고, Phase 2가 쓸 점선 스타일을 미리 넣는다. Layer 1(확정)과 Layer 2(AI 추정)의 영역 목록을 독립적으로 들고 한 창에 함께 그린다.

**Files:**
- Create: `app/src/main/java/com/senioradguard/overlay/AdBorderOverlay.kt`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `enum class AdMarkStyle { CONFIRMED, AI_GUESS }`
  - `class AdBorderOverlay(context: Context)` — `fun show(style: AdMarkStyle, regions: List<Rect>)`, `fun clear(style: AdMarkStyle)`, `fun dismissAll()`

- [ ] **Step 1: 오버레이 작성**

`app/src/main/java/com/senioradguard/overlay/AdBorderOverlay.kt`:

```kotlin
package com.senioradguard.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 확정(공식 라벨) / AI 추정 — 테두리 모양과 배지 문구가 다르다. */
enum class AdMarkStyle { CONFIRMED, AI_GUESS }

/**
 * 광고 영역에 테두리와 배지를 그리는 비차단 오버레이.
 *
 * 팀원 AdDetectService의 showBorders/buildBorderView/setAdRegions/beep에서 이식.
 * FLAG_NOT_TOUCHABLE로 터치를 통과시켜 광고 클릭·구매·설치 선택을 일절 방해하지
 * 않는다 (구글 정책). 이 플래그는 절대 제거하면 안 된다.
 *
 * Layer 1(CONFIRMED)과 Layer 2(AI_GUESS)의 영역 목록을 따로 들고 한 창에 함께
 * 그린다. 한쪽이 갱신돼도 다른 쪽은 유지된다.
 */
class AdBorderOverlay(private val context: Context) {

    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val vibrator by lazy {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val handler = Handler(Looper.getMainLooper())

    private var overlay: FrameLayout? = null
    private val shown = mutableMapOf<AdMarkStyle, List<Rect>>()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** 해당 스타일의 영역 목록을 교체한다. 다른 스타일의 표시는 그대로 둔다. */
    fun show(style: AdMarkStyle, regions: List<Rect>) {
        if (shown[style].orEmpty() == regions) return

        val wasEmpty = shown.values.all { it.isEmpty() }
        shown[style] = regions

        if (shown.values.all { it.isEmpty() }) {
            removeOverlay()
            return
        }

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (wasEmpty && regions.isNotEmpty()) {
            if (prefs.getBoolean("sound", true)) beep()
            if (prefs.getBoolean("vibe", true)) vibrate()
        }
        if (prefs.getBoolean("visual", true)) render()
    }

    fun clear(style: AdMarkStyle) = show(style, emptyList())

    fun dismissAll() {
        shown.clear()
        removeOverlay()
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun render() {
        if (overlay == null) {
            overlay = FrameLayout(context)
            windowManager.addView(
                overlay,
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
            )
        }
        overlay?.apply {
            // 오버레이 창은 상태바 아래에서 시작할 수 있으므로 실제 창 위치만큼 좌표를 보정
            post {
                val loc = IntArray(2).also { getLocationOnScreen(it) }
                val win = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
                removeAllViews()
                for ((style, regions) in shown) {
                    for (r in regions) {
                        val c = Rect(r)
                        // 창 밖·너무 얇은 조각은 생략
                        if (!c.intersect(win) || c.height() < dp(40)) continue
                        addView(
                            buildBorderView(style, c.height()),
                            FrameLayout.LayoutParams(c.width(), c.height()).apply {
                                setMargins(c.left - win.left, c.top - win.top, 0, 0)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun buildBorderView(style: AdMarkStyle, regionHeight: Int): FrameLayout {
        val accent = when (style) {
            AdMarkStyle.CONFIRMED -> Color.parseColor("#FF5722")   // 주황
            AdMarkStyle.AI_GUESS -> Color.parseColor("#FFC107")    // 노랑
        }
        val badgeLabel = when (style) {
            AdMarkStyle.CONFIRMED -> "AD"
            AdMarkStyle.AI_GUESS -> "AI"
        }
        val badgeText = when (style) {
            AdMarkStyle.CONFIRMED -> "광고"
            AdMarkStyle.AI_GUESS -> "광고 같아요"
        }

        val badge = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 20, 20, 20))
                cornerRadius = dp(14).toFloat()
            }
            addView(TextView(context).apply {
                text = badgeLabel
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(10).toFloat()
                }
            })
            addView(TextView(context).apply {
                text = badgeText
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(dp(10), 0, 0, 0)
            })
        }

        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                when (style) {
                    AdMarkStyle.CONFIRMED -> setStroke(dp(6), accent)
                    // AI 추정은 점선 — 확정과 시각적으로 구분해 오탐 시 오해를 줄인다
                    AdMarkStyle.AI_GUESS ->
                        setStroke(dp(6), accent, dp(12).toFloat(), dp(8).toFloat())
                }
            }
            // 영역이 배지를 담기에 너무 좁으면 테두리만 표시
            if (regionHeight >= dp(80)) {
                addView(
                    badge,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(12), dp(12), 0, 0) }
                )
            }
        }
    }

    private fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        handler.postDelayed({ tone.release() }, 400)
    }

    private fun vibrate() {
        // minSdk 26이므로 VibrationEffect를 무조건 쓸 수 있다
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add app/src/main/java/com/senioradguard/overlay/AdBorderOverlay.kt
git commit -m "feat: 비차단 광고 테두리 오버레이 이식 (AdBorderOverlay)

팀원 AdDetectService의 showBorders/buildBorderView/beep/진동을 이식하고,
Layer 2가 쓸 AI_GUESS 점선 스타일(노랑, 'AI 광고 같아요')을 추가.

CONFIRMED(공식 라벨, 주황 실선)와 AI_GUESS 영역 목록을 독립적으로 들고 한
창에 함께 그려서 한쪽 갱신이 다른 쪽을 지우지 않는다.

TYPE_ACCESSIBILITY_OVERLAY + FLAG_NOT_TOUCHABLE 유지 — 광고 클릭·구매·설치를
방해하지 않는다(구글 정책).

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6"
```

---

### Task 7: 단일 서비스 통합 — Layer 1 배선 + 구 코드 제거

`AdGuardAccessibilityService`를 단일 진입점으로 재작성한다. Layer 1을 배선하고, MediaProjection·ML Kit OCR 경로와 열등한 배너 구현을 제거한다.

**Files:**
- Modify: `app/src/main/java/com/senioradguard/service/AdGuardAccessibilityService.kt` (전체 재작성)
- Modify: `app/src/main/java/com/senioradguard/detector/AdDetector.kt` (`adLabelPackages`, `isAdLabelPackage`/`matchesAdLabel`, `scoreByAI`, `textRecognizer`, 관련 import 삭제)
- Modify: `app/src/main/java/com/senioradguard/overlay/OverlayManager.kt` (`showAdInfoBanner`/`dismissAdInfoBanner` 및 전용 필드 삭제)
- Modify: `app/src/main/java/com/senioradguard/MainActivity.kt` (MediaProjection 권한 요청 코드 삭제)
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `app/build.gradle.kts` (ML Kit 의존성 삭제)
- Delete: `app/src/main/java/com/senioradguard/detector/ScreenCaptureService.kt`
- Delete: `app/src/main/java/com/senioradguard/detector/ScreenCaptureHelper.kt`

**Interfaces:**
- Consumes: `AdRegionScanner.scan()` (Task 5), `AdBorderOverlay.show/dismissAll` + `AdMarkStyle` (Task 6)
- Produces: `AdGuardAccessibilityService` — Layer 3 배선은 Task 8에서 추가

- [ ] **Step 1: 접근성 서비스 설정에 스크린샷 권한 추가**

`app/src/main/res/xml/accessibility_service_config.xml` 전체를 다음으로 교체 (`canTakeScreenshot`은 Phase 2의 Agent3가 쓴다):

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/app_name"
    android:notificationTimeout="100" />
```

- [ ] **Step 2: 서비스 재작성**

`app/src/main/java/com/senioradguard/service/AdGuardAccessibilityService.kt` 전체를 다음으로 교체:

```kotlin
package com.senioradguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.senioradguard.overlay.AdBorderOverlay
import com.senioradguard.overlay.AdMarkStyle
import com.senioradguard.region.AdRegionScanner

/**
 * 단일 진입점. 이벤트를 받아 루트 노드를 한 번만 가져오고 각 레이어에 배분한다.
 *
 *   Layer 1  공식 광고 라벨 감지  → 비차단 테두리 (AdBorderOverlay)
 *   Layer 2  LLM 판별            → Phase 2에서 추가
 *   Layer 3  설치 유도 감지       → 차단 경고 (Task 8에서 추가)
 */
class AdGuardAccessibilityService : AccessibilityService() {

    private val targetApps = setOf(
        "com.google.android.youtube",   // 유튜브
        "com.instagram.android",        // 인스타그램
        "com.towneers.www",             // 당근
        "com.android.chrome",           // 크롬 (모바일 웹)
        "com.sec.android.app.sbrowser"  // 삼성 인터넷 (모바일 웹)
    )

    private val scanner = AdRegionScanner()
    private val borderOverlay by lazy { AdBorderOverlay(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())

    private var lastScan = 0L

    // 이벤트가 끊겨도 광고가 아직 화면에 있는지 직접 재확인하고, 사라졌을 때만 표시를 해제
    private val recheck = Runnable {
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() in targetApps) {
            applyLayer1(scanner.scan(root))
        } else {
            applyLayer1(emptyList())
        }
    }

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (pkg !in targetApps) {
            // 다른 앱 화면으로 전환되면 테두리 해제
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != "com.android.systemui" && pkg != packageName
            ) {
                applyLayer1(emptyList())
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastScan < 200) return
        lastScan = now

        val root = rootInActiveWindow ?: return
        applyLayer1(scanner.scan(root))
    }

    private fun applyLayer1(regions: List<Rect>) {
        handler.removeCallbacks(recheck)
        if (regions.isNotEmpty()) handler.postDelayed(recheck, 1000)
        borderOverlay.show(AdMarkStyle.CONFIRMED, regions)
    }

    override fun onInterrupt() {
        applyLayer1(emptyList())
    }

    override fun onDestroy() {
        handler.removeCallbacks(recheck)
        borderOverlay.dismissAll()
        super.onDestroy()
    }
}
```

- [ ] **Step 3: MediaProjection 관련 파일 삭제**

```bash
rm app/src/main/java/com/senioradguard/detector/ScreenCaptureService.kt
rm app/src/main/java/com/senioradguard/detector/ScreenCaptureHelper.kt
```

- [ ] **Step 4: `AdDetector`에서 대체된 코드 삭제**

`AdDetector.kt`에서 다음을 삭제:
- `adLabelPackages` 필드 전체 (주석 포함)
- `isAdLabelPackage()` / `matchesAdLabel()` (주석 포함)
- `scoreByAI()` (주석 포함)
- `textRecognizer` 필드
- 관련 import:
  ```kotlin
  import android.graphics.Bitmap
  import com.google.mlkit.vision.common.InputImage
  import com.google.mlkit.vision.text.TextRecognition
  import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
  import kotlinx.coroutines.suspendCancellableCoroutine
  import kotlin.coroutines.resume
  ```

`app/build.gradle.kts`에서 ML Kit 의존성 2줄 삭제:

```kotlin
    // ML Kit (AI 이미지 분석)
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
```

- [ ] **Step 5: `OverlayManager`에서 배너 코드 삭제**

`OverlayManager.kt`에서 `showAdInfoBanner()`, `dismissAdInfoBanner()`, 그리고 이 둘만 쓰는 필드(`bannerView`, `bannerDismissRunnable` 등)를 삭제한다. `showWarning()`과 `dismiss()`는 Layer 3에서 계속 쓰므로 남긴다.

- [ ] **Step 6: `MainActivity`에서 MediaProjection 권한 요청 삭제**

`MainActivity.kt`에서 `MediaProjectionManager`, `createScreenCaptureIntent()`, `ScreenCaptureService` 기동 관련 코드와 import를 모두 삭제한다. `BlacklistUpdateWorker.schedule(this)` 호출과 `SetupActivity` 이동 로직은 남긴다.

- [ ] **Step 7: Manifest 정리**

`app/src/main/AndroidManifest.xml`에서 삭제:
- `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />`
- `ScreenCaptureService`의 `<service>` 선언 전체

남길 것: `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `INTERNET`, 접근성 서비스 선언, `<queries>com.kakao.talk</queries>`

- [ ] **Step 8: 컴파일 + 단위 테스트 확인**

Run: `./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 실패 테스트 0건

- [ ] **Step 9: 실기기 검증 — 모바일 웹 (크롬)**

```bash
adb devices -l                    # device 상태 확인
bash scripts/redeploy.sh          # 설치 → 접근성 재활성화 → 앱 실행
adb logcat -c
```

기기에서 직접 조작:
1. 크롬을 열고 광고가 실린 뉴스 사이트로 이동 (예: `news.naver.com`, `sports.news.naver.com`)
2. 스크롤하며 배너 광고가 나오는 지점까지 이동

확인할 것:
- 광고 영역에 **주황 실선 테두리 + "AD 광고" 배지**가 뜬다
- 테두리 위를 손으로 터치했을 때 **아래 페이지가 정상 반응한다** (터치 통과 — 링크가 눌리거나 스크롤됨)
- 광고를 지나쳐 스크롤하면 테두리가 사라진다
- 홈 버튼으로 크롬을 벗어나면 테두리가 사라진다

스크린샷 저장:
```bash
adb shell screencap -p /sdcard/layer1_chrome.png
adb pull /sdcard/layer1_chrome.png
```

터치 패스스루가 적용됐는지 로그로 확인:
```bash
adb logcat -d | grep -i "FLAG_NOT_TOUCHABLE"
```
Expected: `setting alpha to 0.80 to let touches pass through` 유사 메시지

- [ ] **Step 10: 실기기 검증 — 유튜브**

에뮬레이터에서는 재현할 수 없었던 항목이다. 실기기에서는 실제 광고로 확인할 수 있다.

기기에서:
1. 유튜브 앱을 열고 아무 영상이나 재생
2. 인스트림 광고가 나올 때까지 대기 (또는 여러 영상을 연속 재생)
3. 홈 피드를 스크롤해 스폰서 카드가 나오는 지점 확인

확인할 것:
- 광고 재생 중 화면에 **주황 실선 테두리**가 뜬다
- 테두리가 떠 있는 동안에도 **"광고 건너뛰기" 버튼이 정상적으로 눌린다** (터치 통과 — 이게 안 되면 구글 정책 위반이므로 즉시 중단하고 원인 파악)
- 광고가 끝나면 테두리가 사라진다

```bash
adb shell screencap -p /sdcard/layer1_youtube.png && adb pull /sdcard/layer1_youtube.png
```

- [ ] **Step 11: 실기기 검증 — 인스타그램**

기기에서 인스타그램 피드를 스크롤해 "Sponsored" / "광고" 게시물을 찾는다.

확인할 것:
- 광고 게시물 카드에 테두리가 뜬다 (헤더·본문이 별도 피드 항목이라 `containerOf`의 피드 항목 병합 로직이 동작하는 지점)
- 좋아요·댓글 버튼이 정상적으로 눌린다 (터치 통과)
- 릴스에서도 광고 라벨이 감지된다 (depth 60 대응 확인)

```bash
adb shell screencap -p /sdcard/layer1_instagram.png && adb pull /sdcard/layer1_instagram.png
```

문제가 발견되면 이식 과정에서 상수나 로직이 바뀌지 않았는지 팀원 원본(`C:\Users\skdla\Downloads\AdDetectService.kt`)과 대조할 것.

- [ ] **Step 12: 커밋**

```bash
git add -A
git commit -m "feat: 단일 AccessibilityService로 통합, Layer 1 배선

AdGuardAccessibilityService를 단일 진입점으로 재작성하고 Layer 1(공식 광고
라벨 → 비차단 테두리)을 연결. 이벤트당 루트 노드를 한 번만 가져와 각 레이어가
공유하고, 사용자는 접근성 스위치를 하나만 켜면 된다.

제거:
- ScreenCaptureService / ScreenCaptureHelper (MediaProjection)
  → 권한 다이얼로그가 사라짐. Phase 2는 canTakeScreenshot을 쓴다
- AdDetector.scoreByAI (ML Kit OCR) 및 ML Kit 의존성
- AdDetector.adLabelPackages / isAdLabelPackage / matchesAdLabel
- OverlayManager.showAdInfoBanner / dismissAdInfoBanner
  → AdBorderOverlay가 상위 호환

실기기(크롬/유튜브/인스타그램)에서 테두리 표시와 터치 통과 검증 완료.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6"
```

---

### Task 8: Layer 3 분리 + 카카오 알림 재전송 연결

설치 유도 감지를 `InstallGuard`로 분리해 서비스에 배선한다. 그리고 현재 실패한 알림이 쌓이기만 하고 재전송되지 않는 구멍(`PendingNotificationQueue.drain()` 미호출)을 막는다.

**Files:**
- Create: `app/src/main/java/com/senioradguard/guard/InstallGuard.kt`
- Modify: `app/src/main/java/com/senioradguard/service/AdGuardAccessibilityService.kt`
- Modify: `app/src/main/java/com/senioradguard/notification/KakaoNotifier.kt`

**Interfaces:**
- Consumes: `OverlayManager.showWarning(...)`, `KakaoNotifier`, `AdEventLogger`
- Produces: `class InstallGuard(...)` — `fun onStoreRedirect(storePackage: String)`, `fun onClick(text: String, packageName: String)`, `fun isStorePackage(pkg: String): Boolean`

- [ ] **Step 1: `InstallGuard` 작성**

`app/src/main/java/com/senioradguard/guard/InstallGuard.kt`:

```kotlin
package com.senioradguard.guard

import android.os.Handler
import android.os.Looper
import com.senioradguard.logger.AdEventLogger
import com.senioradguard.overlay.OverlayManager

/**
 * Layer 3 — 앱 설치 유도 감지.
 *
 * Layer 1·2와 달리 여기서는 터치를 막는다. 광고 클릭 방해가 아니라 앱이 실제로
 * 설치되기 직전에 개입하는 것이라 성격이 다르다.
 *
 * @param onBack       "뒤로 가기" 선택 시 실행 (performGlobalAction(GLOBAL_ACTION_BACK))
 * @param onForceHome  경고 후에도 화면이 바뀌지 않을 때 홈으로 (GLOBAL_ACTION_HOME)
 * @param currentForegroundPackage 현재 최상단 패키지 조회
 */
class InstallGuard(
    private val overlayManager: OverlayManager,
    private val onBack: () -> Unit,
    private val onForceHome: () -> Unit,
    private val currentForegroundPackage: () -> String?
) {

    private val handler = Handler(Looper.getMainLooper())

    private val storePackages = setOf(
        "com.android.vending",              // Google Play Store
        "com.sec.android.app.samsungapps"   // Samsung Galaxy Store
    )

    private val adKeywords = setOf(
        "설치하기", "지금 설치", "무료 다운로드", "앱 다운로드",
        "install now", "free download", "get app",
        "광고", "이벤트 참여", "지금 받기", "혜택 받기"
    )

    fun isStorePackage(pkg: String): Boolean = pkg in storePackages

    /** Play Store / 갤럭시 스토어로 강제 이동했을 때. */
    fun onStoreRedirect(storePackage: String) {
        overlayManager.showWarning(
            message = "앱 설치 화면으로 이동했어요!\n광고로 인한 이동일 수 있습니다.\n뒤로 돌아갈까요?",
            packageName = storePackage,
            onConfirm = { /* 사용자 선택으로 설치 허용 */ },
            onBlock = onBack,
            currentForegroundPackage = currentForegroundPackage,
            onForceHome = onForceHome
        )
        AdEventLogger.logStoreRedirect(storePackage)
    }

    /** "설치하기" 등 위험 버튼을 눌렀을 때. 설치가 실행되기 전에 끼어든다. */
    fun onClick(text: String, packageName: String) {
        if (!isAdTriggerText(text)) return
        handler.postDelayed({
            overlayManager.showWarning(
                message = "광고일 수 있습니다!\n'$text' 버튼을 눌렀어요.\n앱이 설치될 수 있으니 확인해주세요.",
                packageName = packageName,
                onConfirm = { /* 사용자가 허용 */ },
                onBlock = onBack,
                currentForegroundPackage = currentForegroundPackage,
                onForceHome = onForceHome
            )
        }, 50)
    }

    private fun isAdTriggerText(text: String): Boolean =
        adKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
}
```

- [ ] **Step 2: 서비스에 Layer 3 배선**

`AdGuardAccessibilityService.kt`에 import 추가:

```kotlin
import com.senioradguard.guard.InstallGuard
import com.senioradguard.overlay.OverlayManager
```

`borderOverlay` 아래에 필드 추가:

```kotlin
    private val overlayManager by lazy { OverlayManager(applicationContext) }
    private val installGuard by lazy {
        InstallGuard(
            overlayManager = overlayManager,
            onBack = { performGlobalAction(GLOBAL_ACTION_BACK) },
            onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
            currentForegroundPackage = { rootInActiveWindow?.packageName?.toString() }
        )
    }
```

`onAccessibilityEvent`의 `val pkg = ...` 바로 아래에 Layer 3 분기를 추가:

```kotlin
        // ── Layer 3: 설치 유도 감지 (targetApps 여부와 무관하게 항상 동작) ──
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            installGuard.isStorePackage(pkg)
        ) {
            installGuard.onStoreRedirect(pkg)
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.contentDescription?.toString() ?: event.text.joinToString()
            installGuard.onClick(clickedText, pkg)
            // 클릭 이벤트로도 화면이 바뀔 수 있으므로 Layer 1 스캔은 계속 진행한다 (return 하지 않음)
        }
```

`onDestroy()`의 `borderOverlay.dismissAll()` 다음 줄에 추가:

```kotlin
        overlayManager.dismiss()
```

- [ ] **Step 3: 카카오 알림 재전송 연결**

`KakaoNotifier.kt`를 다음과 같이 고친다.

1. 기존 전송 로직에서 **HTTP 호출만 하는** private 함수를 추출한다:

```kotlin
    /** 큐 적재 없이 HTTP 전송만 한다. 재전송 경로에서 재귀 적재를 막기 위한 분리. */
    private fun sendRaw(guardianUuid: String, templateObjectJson: String): Boolean {
        // 기존 HttpURLConnection 전송 코드를 여기로 옮긴다 (enqueue 호출 제외)
    }
```

2. 공개 전송 함수는 `sendRaw`를 호출하고, 실패 시에만 `enqueue`한다:

```kotlin
        val ok = sendRaw(guardianUuid, templateObject.toString())
        if (!ok) {
            PendingNotificationQueue.enqueue(guardianUuid, templateObject.toString())
            return false
        }
        // 이전에 실패해 쌓인 알림을 이 시점에 함께 재전송한다.
        // (기존에는 enqueue만 있고 drain 호출부가 없어 영구히 쌓이기만 했다)
        // 재전송 경로에서는 enqueue를 하지 않으므로 무한 루프가 생기지 않는다.
        PendingNotificationQueue.drain().forEach { (uuid, templateJson) ->
            runCatching { sendRaw(uuid, templateJson) }
        }
        return true
```

- [ ] **Step 4: 컴파일 + 단위 테스트 확인**

Run: `./gradlew compileDebugKotlin testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 실패 테스트 0건

- [ ] **Step 5: 실기기 검증 — Layer 3**

```bash
bash scripts/redeploy.sh
```

기기에서 직접:
1. 크롬으로 아무 페이지나 열고, Play Store로 이동하는 링크를 누른다. 링크가 마땅치 않으면 adb로 흉내:
   ```bash
   adb shell am start -a android.intent.action.VIEW -d "market://details?id=com.kakao.talk"
   ```
2. 경고 팝업이 뜨는지 확인

확인할 것:
- Play Store로 이동하면 **터치 가능한 차단 경고 팝업**이 뜬다
- "뒤로 가기"(빨강, 큰 버튼)를 누르면 실제로 이전 화면으로 돌아간다
- "무시하기"(회색, 작은 버튼)를 누르면 팝업이 닫히고 Play Store가 그대로 보인다
- 크롬에서 광고 테두리(Layer 1)와 이 경고(Layer 3)가 **동시에 떠도 서로 간섭하지 않는다** — 테두리는 터치 통과, 경고 팝업은 터치 수신

```bash
adb shell screencap -p /sdcard/layer3_guard.png && adb pull /sdcard/layer3_guard.png
```

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "feat: Layer 3 InstallGuard 분리 + 카카오 알림 재전송 연결

설치 유도 감지(Play Store 이동, 설치 버튼 클릭)를 InstallGuard로 분리하고
단일 서비스에 배선. Layer 3만 터치 가능 오버레이를 쓴다 — 광고 클릭 방해가
아니라 앱 설치 직전 개입이라 성격이 다르다.

PendingNotificationQueue.drain() 호출부를 연결. 기존에는 enqueue만 있고
drain을 부르는 곳이 없어 실패한 알림이 영구히 쌓이기만 했다. 전송 로직을
sendRaw로 분리해 재전송 경로에서는 enqueue를 하지 않아 무한 루프를 막는다.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01NuAZsQdvbPAm3JvA3QwXq6"
```

---

## Phase 1 완료 조건

- [ ] `./gradlew testDebugUnitTest` 전체 통과 (DomainMatcher 7, BlacklistCache 3, AdLabelRules 10)
- [ ] `./gradlew connectedDebugAndroidTest` 전체 통과 (AdVerdictDao 7) — USB 실기기
- [ ] 실기기에서 version 1 → 2 덮어 설치 시 크래시 없음
- [ ] 실기기 크롬에서 Layer 1 주황 실선 + 터치 통과 확인
- [ ] 실기기 유튜브에서 실제 인스트림 광고 감지 + "광고 건너뛰기" 정상 동작 확인
- [ ] 실기기 인스타그램에서 Sponsored 피드 감지 + 좋아요 버튼 정상 동작 확인
- [ ] 실기기에서 Layer 3 차단 경고 + 뒤로 가기 동작 확인
- [ ] `CLAUDE.md`의 "현재 상태" / "다음 할 일" 섹션을 Phase 1 결과로 갱신

## Phase 2 예고 (별도 계획서)

Phase 1이 만든 `AdRegionScanner` / `AdBorderOverlay(AI_GUESS)` / `AdVerdictDao` 위에 얹는다.

1. `AdCandidate` + `CandidateExtractor` (Agent1)
2. `AdClassifier` 인터페이스 + `LlmClassifier` (claude-haiku-4-5, structured outputs)
3. `VisionClient` + `VisionEnricher` (Agent3, `takeScreenshot()` API 30+)
4. `CrossValidator` (Agent4, viewIdResourceName 약한 신호)
5. `AgentPipeline` (유휴 600ms 디바운스, 캐시, 3건 상한, 토큰버킷)
6. 프라이버시 마스킹 + 옵트인 토글

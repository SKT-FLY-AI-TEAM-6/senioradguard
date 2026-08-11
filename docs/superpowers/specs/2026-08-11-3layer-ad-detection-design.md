# 3-Layer 광고 감지 아키텍처 설계

- 작성일: 2026-08-11
- 상태: 승인 대기
- 대상 저장소: https://github.com/SKT-FLY-AI-TEAM-6/senioradguard

## 배경

현재 두 개의 별도 코드베이스가 존재한다.

| 코드 | 패키지 | 하는 일 |
|---|---|---|
| SeniorAdGuard (이 저장소) | `com.senioradguard` | 설치 유도 감지 + 차단 팝업 + 카카오 보호자 알림 |
| AdDetectService (팀원) | `com.flyai.adalert` | 공식 광고 라벨 감지 + 비차단 테두리 표시 |

두 앱 모두 `AccessibilityService`를 하나씩 가지고 각각 화면 전체 노드 트리를 순회한다. 이를 하나로 합치고, 그 사이에 LLM 기반 판별 레이어를 새로 넣는다.

## 목표

1. 두 서비스를 **단일 `AccessibilityService`**로 통합한다. 사용자는 접근성 스위치를 하나만 켜면 되고, 노드 트리 순회는 이벤트당 한 번만 일어난다.
2. 공식 라벨이 **없는** 광고를 LLM으로 판별하는 Layer 2를 추가한다.
3. 기존 설치 유도 차단(Layer 3)은 유지하되 역할을 명확히 분리한다.

## 비목표

- 광고를 차단·숨김·스킵하지 않는다 (Layer 1·2). 표시만 한다.
- 카카오 비즈 앱 검수 승인 대기 중인 기능은 이번 범위가 아니다.

---

## 1. 아키텍처

### 1.1 단일 진입점

```
onAccessibilityEvent(event)
│
├─ pkg ∈ storePackages           → Layer3.onStoreRedirect()
├─ TYPE_VIEW_CLICKED             → Layer3.onClick(text)
│
└─ pkg ∈ targetApps  (200ms 스로틀)
   │
   ├─ [즉시] Layer1.scan(root)   → List<Rect> → AdBorderOverlay.show(SOLID)
   │
   └─ [600ms 유휴 후] Layer2.run(root) → List<Rect> → AdBorderOverlay.show(DASHED)
```

`rootInActiveWindow`는 이벤트당 한 번만 가져와 세 레이어가 공유한다.

### 1.2 오버레이 분리

| | 창 타입 | 터치 | 용도 |
|---|---|---|---|
| `AdBorderOverlay` | `TYPE_ACCESSIBILITY_OVERLAY` | `FLAG_NOT_TOUCHABLE` | Layer 1·2 광고 표시 |
| `OverlayManager.showWarning` | `TYPE_APPLICATION_OVERLAY` | 터치 가능 | Layer 3 설치 경고 |

Layer 1·2는 터치를 통과시켜 광고 클릭·구매·설치 선택을 일절 방해하지 않는다(구글 정책). 터치를 막는 것은 Layer 3의 설치 유도 경고뿐이며, 이는 광고 클릭 방해가 아니라 앱 설치 직전 개입이라 성격이 다르다.

**시각적 구분**: Layer 1은 공식 라벨 기반이므로 확정 — 주황 실선 + "AD 광고" 배지(기존 그대로). Layer 2는 AI 추정이므로 **노란 점선 + "AI 광고 같아요" 배지**로 다르게 표시한다. 오탐 시 사용자가 확정으로 오해하지 않게 하기 위함이다.

### 1.3 파일 구조

```
com.senioradguard/
├── service/
│   └── AdGuardAccessibilityService.kt   단일 진입점, 레이어 배분만
│
├── region/                              ← Layer 1 (팀원 코드 이식)
│   ├── AdRegionScanner.kt               collectAdRegions / containerOf / adLinkOf
│   └── AdLabelRules.kt                  isAdLabel / isAdContainer / adContainerIds
│
├── overlay/
│   ├── AdBorderOverlay.kt               팀원 showBorders/buildBorderView/beep 이식
│   └── OverlayManager.kt                기존, Layer 3 경고용으로 유지
│
├── agent/                               ← Layer 2
│   ├── AdCandidate.kt                   rect, texts, imageNodes, viewIds, sourceKey
│   ├── CandidateExtractor.kt            Agent1
│   ├── VisionEnricher.kt                Agent3
│   ├── LlmClassifier.kt                 Agent2 구현체
│   ├── CrossValidator.kt                Agent4
│   ├── AgentPipeline.kt                 오케스트레이터 + 유휴 디바운스
│   ├── AdClassifier.kt                  interface (온디바이스/프록시 교체점)
│   ├── VisionClient.kt                  interface (동일)
│   └── db/AdVerdict.kt, AdVerdictDao.kt Room 캐시
│
├── guard/                               ← Layer 3
│   └── InstallGuard.kt                  Play Store 이동 + 설치버튼 클릭 + 카카오 알림
│
├── detector/                            기존
│   ├── AdDetector.kt                    keywordWeights + scoreByBlacklist만 남김
│   ├── BlacklistRepository.kt
│   └── db/…, BlacklistUpdateWorker.kt
└── notification/KakaoNotifier.kt        그대로 재사용
```

### 1.4 삭제 대상

| 파일/심볼 | 사유 |
|---|---|
| `detector/ScreenCaptureService.kt` | `AccessibilityService.takeScreenshot()`으로 대체 |
| `detector/ScreenCaptureHelper.kt` | 동일 |
| `AdDetector.scoreByAI()` | Layer 2의 Vision/LLM이 대체 |
| `AdDetector.adLabelPackages` / `isAdLabelPackage` / `matchesAdLabel` | `AdLabelRules`가 상위 호환 |
| `OverlayManager.showAdInfoBanner()` / `dismissAdInfoBanner()` | `AdBorderOverlay`가 상위 호환 |
| `AdGuardAccessibilityService.analyzeWindowContent()`의 0.6 점수 합산 로직 | Layer 2가 대체 |
| Manifest `FOREGROUND_SERVICE_MEDIA_PROJECTION` 권한 + `ScreenCaptureService` 선언 | MediaProjection 제거 |

MediaProjection 제거는 UX 개선이기도 하다. 기존에는 앱 실행마다 권한 다이얼로그가 뜨고 사용자가 기본값 "Share one app" 대신 "Share entire screen"을 골라야만 동작했다. `AccessibilityService.takeScreenshot()`은 권한 다이얼로그가 전혀 없다.

---

## 2. Layer 1 — 공식 라벨 감지 (이식)

### 2.1 이식 원칙

`collectAdRegions` / `containerOf` / `adLinkOf` / `isAdLabel` / `isAdContainer` / `adContainerIds`의 **로직과 상수는 한 글자도 바꾸지 않는다.** 아래는 모두 실제 앱에서 부딪혀 나온 대응이며 임의로 손대면 회귀한다.

- 유튜브 Litho UI는 `findAccessibilityNodeInfosByText`를 지원하지 않아 직접 순회가 필요
- 인스타그램 릴스는 라벨이 30단계보다 깊어 `depth > 60`
- 릴스 페이저는 화면 밖 요소의 좌표를 어긋나게, 크롬은 높이 0으로 접어서 보고 → `isVisibleToUser` + 크기 > 0 검사
- 광고 문구 사이에 폭 0 문자(`​-‍`, `﻿`)를 끼워 차단을 회피 → 제거 후 매칭
- `" - "`는 양옆 공백이 있을 때만 구분자 → `non-sponsored` 같은 단어가 쪼개지지 않음
- 추천 위젯(Dable·Taboola)은 광고와 진짜 기사가 섞여 있어 컨테이너로 잡으면 안 됨 → 개별 "AD" 라벨로만

변경 사항은 두 가지뿐이다.

1. 패키지명 `com.flyai.adalert` → `com.senioradguard.region` / `com.senioradguard.overlay`
2. `AccessibilityService` 상속 클래스에서 순수 클래스로 분리하면서 `resources` / `getSystemService` 접근을 생성자 `Context` 주입으로 변경

### 2.2 순수 함수 분리

`isAdLabel(String): Boolean`과 `isAdContainer(String?): Boolean`은 `AccessibilityNodeInfo` 의존이 없으므로 `AdLabelRules`로 분리해 JVM 단위 테스트 대상으로 만든다. 트리 순회 부분(`collectAdRegions`)은 계측 테스트 영역으로 남긴다.

---

## 3. Layer 2 — 에이전트 파이프라인

### 3.1 흐름

```
스크롤 멈춤 600ms
   ↓
Agent1  CandidateExtractor    노드트리 → 후보 카드 목록 + 텍스트/이미지 분류
   ↓                          (Layer 1이 이미 잡은 카드는 제외)
        CacheLookup           Room 조회 → hit이면 즉시 반영, LLM 호출 없음
   ↓ miss (최대 3건)
Agent3  VisionEnricher        alt/contentDescription 없는 이미지 → 크롭 → Vision → 텍스트 추가
   ↓
Agent2  LlmClassifier         Claude API → {isAd, confidence, reason}
   ↓
Agent4  CrossValidator        viewIdResourceName 약한 신호로 ±보정
   ↓
        Room 저장 + 카드가 아직 화면에 있으면 점선 테두리
```

Agent3이 Agent2보다 **앞**에 온다. Vision 결과가 LLM의 입력이 되기 때문이다.

### 3.2 Agent1 — CandidateExtractor

개별 노드가 아니라 `containerOf()`가 잡은 **카드 단위**로 후보를 만든다. 피드 한 화면의 노드는 수백 개지만 카드는 5~15개 수준이다.

```kotlin
data class AdCandidate(
    val rect: Rect,
    val texts: List<String>,
    val imageNodes: List<Rect>,   // contentDescription 없는 ImageView 영역
    val viewIds: List<String>,    // viewIdResourceName 모음 (Agent4용)
    val sourceKey: String         // 도메인 또는 패키지명
)
```

제외 조건:
- Layer 1이 이미 광고로 판정한 영역과 겹치는 카드
- `EditText` / `isEditable` 노드를 포함한 카드 (로그인·결제 폼)

### 3.3 Agent3 — VisionEnricher

**`AccessibilityService.takeScreenshot()`(API 30+)** 로 화면을 받아 카드의 `Rect`로 크롭한 뒤 Google Vision API에 보낸다.

- `accessibility_service_config.xml`에 `android:canTakeScreenshot="true"` 추가 필요
- minSdk가 26이므로 **API 30 미만에서는 Agent3만 비활성화**하고 나머지 파이프라인은 그대로 동작
- 호출 조건: 이미지 노드가 있고 **카드 텍스트가 20자 미만**일 때만. 텍스트가 충분하면 Vision 없이 LLM으로 직행
- Bitmap은 사용 후 즉시 `recycle()`

### 3.4 Agent2 — LlmClassifier

**모델: `claude-haiku-4-5`.**

분류 작업이고 카드마다 호출되므로 지연·비용이 품질보다 중요하다는 판단이다. Anthropic 기본 권장은 `claude-opus-5`이며, 판별 정확도가 부족하면 `AdClassifier` 인터페이스 뒤에서 모델 문자열만 바꿔 올릴 수 있다.

요청 구성:

| 항목 | 값 |
|---|---|
| `model` | `claude-haiku-4-5` |
| `max_tokens` | 256 |
| `output_config.format` | `json_schema` — `{isAd: boolean, confidence: number, reason: string}` |
| 입력 텍스트 | 400자로 절단 |
| 타임아웃 | 8초 |

`output_config.format`(structured outputs)을 쓰면 응답이 스키마를 만족하도록 강제되어 JSON 파싱 실패가 원천적으로 사라진다.

**프롬프트 캐싱**: 분류 지침(시스템 프롬프트)에 `cache_control` 브레이크포인트를 둘 수 있으나, Haiku 4.5의 최소 캐시 프리픽스는 **4096 토큰**이다. 분류 지침이 이 선을 넘을 일은 거의 없고, 넘지 않으면 캐시는 에러 없이 조용히 동작하지 않는다(`cache_creation_input_tokens: 0`). **따라서 이번 구현에서는 프롬프트 캐싱을 넣지 않는다.** 지침이 4096 토큰을 넘도록 커지면 그때 추가한다.

**SDK 선택**: `com.anthropic:anthropic-java`는 JVM용이라 안드로이드에서 Jackson·desugaring 충돌 가능성이 있다. 먼저 SDK로 붙이고 빌드가 깨지면 프로젝트가 이미 일관되게 쓰는 `HttpURLConnection`으로 대체한다. `AdClassifier` 인터페이스 뒤라 어느 쪽이든 나머지 코드는 영향받지 않는다.

**API 키**: 온디바이스(`local.properties` → `BuildConfig`). 추출 가능하므로 **프로덕션 배포 전 프록시 서버 전환 필수**임을 코드 주석과 이 문서에 명시한다. `AdClassifier` / `VisionClient` 인터페이스가 그 교체점이다.

### 3.5 Agent4 — CrossValidator

원래 요구사항은 "HTML 클래스명(ad, sponsored, advertisement) 교차검증"이었으나 **안드로이드 접근성 API로는 불가능하다.** 크롬이 노출하는 것은 HTML `id` 속성뿐이며(`viewIdResourceName`), CSS `class`는 접근성 트리에 실리지 않는다. `AccessibilityNodeInfo.className`은 안드로이드 위젯 클래스(`android.widget.TextView`)라 무관하다.

가능한 범위로 대체한다.

```
확정 신호 (기존 adContainerIds, LLM 없이 바로 광고)
  div-gpt-ad / adsbygoogle / google_ads / aceplanet /
  mobondivbanner / adfit / clickads / innorame / criteo

약한 신호 (신규, LLM 교차검증용)
  viewIdResourceName에 ad / ads / sponsor / banner / promo / pr_ 포함

최종 = LLM confidence ± 약한 신호 보정
  일치  → +0.15
  불일치 → -0.15
  임계값 0.6 이상일 때만 점선 표시
```

### 3.6 실행 시점

`TYPE_WINDOW_CONTENT_CHANGED`가 **600ms 동안 오지 않으면**(스크롤 정지) 파이프라인을 돌린다. `Handler.postDelayed` + `removeCallbacks` 디바운스.

LLM 왕복은 1~3초라 응답이 왔을 때 사용자가 이미 스크롤했을 수 있다. 응답 도착 시 해당 카드가 **아직 화면에 있으면** 테두리를 표시하고, 없으면 **DB에만 기록**한다. 다음에 같은 카드가 나타나면 캐시 히트로 즉시 표시된다.

---

## 4. Layer 3 — 설치 유도 차단 (기존 유지)

기존 코드를 `guard/InstallGuard.kt`로 옮기되 로직은 유지한다.

| 트리거 | 동작 |
|---|---|
| `TYPE_WINDOW_STATE_CHANGED` + `storePackages` | Play Store/갤럭시스토어 강제 이동 경고 |
| `TYPE_VIEW_CLICKED` + `adKeywords` 매칭 | 50ms 딜레이 후 설치 버튼 클릭 경고 |

경고는 `OverlayManager.showWarning()`(터치 가능) — "뒤로 가기"(빨강, 크게) / "무시하기"(회색, 작게). "뒤로 가기"는 `performGlobalAction(GLOBAL_ACTION_BACK)`.

보호자 알림은 `KakaoNotifier`를 그대로 재사용한다. 카카오 비즈 앱 검수 승인 전이므로 메시지 API 호출은 실패하고 `PendingNotificationQueue.enqueue()`에 쌓인다.

**이번에 함께 고칠 것**: `PendingNotificationQueue.drain()`을 호출하는 곳이 현재 아무 데도 없어 실패한 알림이 쌓이기만 하고 재전송되지 않는다. 알림 전송 성공 시 또는 주기 워커에서 `drain()`을 호출해 재전송을 연결한다.

---

## 5. 데이터베이스

### 5.1 현재 구조

Room DB 하나 `senior_ad_guard.db`, **version 1**, `exportSchema = false`, 싱글턴. 테이블은 `blacklist_domains`(`domain` PK, `updatedAt`) 하나뿐이다.

```
AdDetector → BlacklistRepository → BlacklistDao → blacklist_domains
```

읽기는 `AdDetector.getCachedDomains()`가 최초 1회 전체를 `Set<String>`으로 메모리에 올려 재사용한다(Mutex 보호). 쓰기는 `BlacklistUpdateWorker`가 주 1회 `refreshFromRemote()` → `replaceAll()`.

### 5.2 현재 구조의 문제 (이번에 함께 고침)

**(a) 조회가 O(n) 선형 탐색** — `AdDetector.kt:101`

```kotlin
if (domains.any { blocked -> domain.endsWith(blocked) })
```

`domains`는 144,135개다. URL 하나마다 최대 14만 번 `endsWith`를 돈다. `Set`이지만 `endsWith` 비교라 해시가 쓰이지 않는다.

수정: 도메인을 뒤에서부터 잘라 올라가며 **정확 일치**로 조회한다. `ads.example.co.kr` → `"ads.example.co.kr"`, `"example.co.kr"`, `"co.kr"`, `"kr"` 순으로 `HashSet.contains()`. 14만 번 → 최대 4~5번.

**(b) 메모리 상주** — 14만 개 String이 프로세스 내내 잡혀 있다(수 MB). 항상 떠 있어야 하는 접근성 서비스에는 부담이다. (a)를 고치면 조회는 빨라지지만 메모리는 그대로이므로, 필요 시 후속 과제로 DB 직접 조회 + LRU 캐시 전환을 검토한다. 이번 범위에서는 (a)만 고친다.

**(c) 갱신이 실행 중 프로세스에 반영되지 않음** — `BlacklistUpdateWorker`는 `repository.refreshFromRemote()`를 직접 호출한다. 메모리 캐시를 비우는 `AdDetector.updateBlacklist()`는 **호출하는 곳이 없는 dead code**(`AdDetector.kt:156`). 따라서 주 1회 갱신분은 앱 프로세스가 완전히 죽었다 살아나야 적용된다.

수정: 워커가 갱신에 성공하면 캐시 무효화 신호를 보내도록 연결한다(정적 플래그 또는 `AdDetector` 캐시 무효화 진입점).

**(d) 마이그레이션 부재 — 이번 작업에서 즉시 터짐**

`@Database(version = 1)`에 마이그레이션도 `fallbackToDestructiveMigration()`도 없다. `AdVerdict` 엔티티를 추가하고 version 2로 올리는 순간 **기존 설치 기기에서 `IllegalStateException` 크래시**한다.

수정: `Room.databaseBuilder(...).fallbackToDestructiveMigration().build()`. 블랙리스트는 워커가 다시 받아오고 판정은 캐시이므로 파괴적 마이그레이션이 안전하며, 손으로 쓴 마이그레이션보다 실수 여지가 적다.

### 5.3 신규 테이블

```kotlin
@Entity(tableName = "ad_verdict")
data class AdVerdict(
    @PrimaryKey val key: String,   // "$sourceKey|$textHash"
    val isAd: Boolean,
    val confidence: Float,
    val source: String,            // LLM / VISION_LLM / VIEWID
    val updatedAt: Long
)
```

- **`sourceKey`** — 브라우저면 주소창 노드(`com.android.chrome:id/url_bar`)에서 읽은 도메인, 앱이면 패키지명
- **`textHash`** — 카드 텍스트를 소문자화 → 공백 정규화 → **숫자를 `#`으로 치환** → 앞 200자 SHA-256. 숫자 치환은 카운트다운·가격이 매번 달라 히트율을 떨어뜨리는 것을 막기 위함이다
- **TTL 30일** — 조회 시 만료 행 무시, 정리는 기존 주 1회 워커에 추가
- **부정 판정(`isAd=false`)도 캐시한다.** 절감의 대부분이 여기서 나온다 — 카드 대부분은 광고가 아니다

---

## 6. 비용·부하 제어

| 제한 | 값 |
|---|---|
| 유휴 1회당 LLM 호출 | 최대 3건 |
| 시간당 LLM 호출 | 60건 (토큰버킷) |
| Vision 호출 조건 | 이미지 있고 카드 텍스트 20자 미만 |
| LLM 입력 텍스트 | 400자 절단 |
| LLM 타임아웃 | 8초 |
| Vision 타임아웃 | 5초 |

상한 초과 시 LLM을 호출하지 않고 캐시 조회 결과만 사용한다.

---

## 7. 프라이버시

Layer 2는 노인 사용자가 보고 있는 화면의 텍스트와 이미지를 외부 서버(Anthropic, Google)로 보낸다. 다음 다섯 가지로 범위를 좁힌다.

1. **`targetApps`에서만 동작** — 유튜브 / 인스타그램 / 당근 / 크롬 / 삼성 인터넷. Layer 1과 동일한 화이트리스트. 은행·메신저는 대상이 아니다
2. **카드 영역만 전송** — 전체 화면이 아니라 `containerOf()`가 잡은 `Rect`로 크롭
3. **입력 필드를 포함한 카드 제외** — `EditText` / `isEditable` 노드가 있으면 로그인·결제 폼일 가능성이 높으므로 후보에서 제거
4. **전송 전 로컬 마스킹** — 전화번호·카드번호·주민등록번호 패턴을 정규식으로 `#` 치환
5. **기본 OFF 옵트인** — 설정에서 "AI 광고 판별"을 명시적으로 켜야 동작. Layer 1과 Layer 3은 이 토글과 무관하게 항상 동작

---

## 8. 에러 처리

| 상황 | 동작 |
|---|---|
| API 키 미설정 | Layer 2 전체 비활성화, 최초 1회만 로그. Layer 1/3 영향 없음 |
| LLM 타임아웃·네트워크 실패 | `unknown` 처리 — 배너 미표시, **캐시에 기록하지 않음** |
| Vision 실패 | 텍스트만으로 Agent2 진행 |
| API 30 미만 기기 | Agent3만 비활성화, 나머지 정상 동작 |
| `takeScreenshot()` 실패 | 동일 — 텍스트만으로 진행 |
| Room 예외 | catch 후 캐시 미스로 처리, 크래시 없음 |
| 응답 스키마 위반 | structured outputs가 막지만 방어적으로 `unknown` 처리 |
| 카카오 전송 실패 | `PendingNotificationQueue.enqueue()` + `drain()` 재전송 연결 |

---

## 9. 테스트

### 9.1 단위 테스트 (`app/src/test`, JVM)

- `AdLabelRules.isAdLabel()` — 구분자 토큰화(`·`, `,`, `" - "`), zero-width 문자 제거, **`non-sponsored`가 오탐되지 않을 것**, 대소문자 무시
- `AdLabelRules.isAdContainer()` — `div-gpt-ad` 계열 매칭, 무관한 id 미매칭
- `CrossValidator` — 약한 신호 ±0.15 보정 경계값
- 캐시 키 정규화 — 숫자 치환·공백 정규화가 같은 카드를 같은 키로 만드는지
- 블랙리스트 도메인 조회 — 접미사 분해 방식이 기존 `endsWith` 방식과 동일한 결과를 내는지 (회귀 방지)
- `AgentPipeline` — 가짜 `AdClassifier` / `VisionClient` 주입해 API 호출 없이 검증: 캐시 히트 시 LLM 미호출, 유휴당 3건 상한, 토큰버킷 동작

### 9.2 계측 테스트 (`app/src/androidTest`, in-memory Room)

- `AdVerdictDao` CRUD + TTL 만료
- `BlacklistDao` / `BlacklistRepository` — 기존 미완료 과제였던 항목을 여기서 해소
- version 2 파괴적 마이그레이션이 크래시 없이 동작하는지

### 9.3 에뮬레이터 수동 검증

- 크롬으로 광고 실린 페이지 → Layer 1 주황 실선
- 라벨 없는 배너 → Layer 2 노란 점선 + "AI 광고 같아요"
- Play Store 이동 → Layer 3 경고 팝업
- 세 오버레이가 동시에 떠도 서로 간섭하지 않는지
- 터치 패스스루가 실제로 적용되는지 (시스템 로그 `FLAG_NOT_TOUCHABLE ... setting alpha`)

**주의**: `adb shell am force-stop` 또는 재설치를 하면 Android가 `enabled_accessibility_services` 설정을 자동으로 지운다. 테스트 순서는 반드시 **force-stop(또는 재설치) → 접근성 서비스 재활성화 → 앱 실행**이어야 한다. 반대로 하면 서비스가 "활성화됨"으로 보여도 실제로 이벤트를 받지 못한다.

---

## 10. 결정 사항 요약

| 항목 | 결정 |
|---|---|
| 서비스 구조 | 단일 `AccessibilityService`로 통합 |
| API 키 | 온디바이스(`BuildConfig`), 인터페이스 뒤에 두어 프록시 전환 가능. **프로덕션 전 전환 필수** |
| Agent4 교차검증 | HTML 클래스명은 불가 → `viewIdResourceName` 약한 신호로 대체 |
| Layer 2 트리거 | 카드 단위 + 스크롤 유휴 600ms, 유휴당 최대 3건 |
| 기존 파이프라인 | Layer 3 전용으로 축소, MediaProjection·ML Kit OCR 제거 |
| 화면 캡처 | `AccessibilityService.takeScreenshot()` (API 30+) |
| LLM 모델 | `claude-haiku-4-5` |
| Layer 2 내부 구조 | 순차 파이프라인 클래스 4개 + 오케스트레이터 |
| DB 마이그레이션 | `fallbackToDestructiveMigration()` |

## 11. 열린 항목 (이번 범위 밖)

- 카카오 비즈 앱 전환 + 친구/메시지 API 검수 승인
- 블랙리스트 메모리 상주(수 MB) 해소 — DB 직접 조회 + LRU 전환 검토
- `POST_NOTIFICATIONS` 런타임 권한 요청 플로우 (Android 13+)
- 프로덕션용 프록시 서버 구축

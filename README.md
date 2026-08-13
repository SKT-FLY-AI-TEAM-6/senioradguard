# SeniorAdGuard

노인 사용자가 광고를 광고로 인식하지 못해 불필요한 앱을 설치하거나 개인정보를 입력하는 피해를 막는 Android 앱.

화면에 뜬 광고에 **테두리를 쳐서 광고임을 알려주고**, 앱 설치가 실행되기 직전에 **끼어들어 확인을 받는다**. 감지 내역은 보호자 폰에 실시간으로 전달된다.

- Kotlin / Jetpack Compose (Material3)
- 화면 감지: AccessibilityService
- 광고 판별: Gemini API
- 보호자 연동: Firebase Realtime Database
- Min SDK 26 (Android 8.0) / target·compile SDK 36

---

## 3-Layer 구조

`AdGuardAccessibilityService` 하나가 이벤트를 받아 루트 노드를 한 번만 가져오고 각 레이어에 배분한다.

| | 무엇을 잡나 | 표시 | 터치 |
|---|---|---|---|
| **Layer 1** | 공식 광고 표기(광고·Sponsored·AD)와 광고 네트워크 컨테이너 id | 주황 **실선** + "AD 광고" | 통과 |
| **Layer 2** | 라벨 없는 광고를 LLM으로 추정 | 노랑 **점선** + "AI 광고 같아요" | 통과 |
| **Layer 3** | 앱 설치 유도(스토어 이동, 설치 버튼 클릭) | 차단 경고 팝업 | **수신** |

Layer 1·2 오버레이는 `TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_NOT_TOUCHABLE`이다. **이 플래그는 절대 제거하면 안 된다** — 광고 클릭·구매·설치를 방해하면 구글 정책 위반이다. Layer 3만 터치를 받는데, 이는 광고 방해가 아니라 설치 직전 개입이라 성격이 다르다.

### "AD 광고"와 "AI 광고 같아요"의 차이

같은 판정의 강약이 아니라 **근거가 다른 별개 경로**다.

| | **AD 광고** (주황 실선) | **AI 광고 같아요** (노랑 점선) |
|---|---|---|
| 근거 | 화면에 **"광고"라고 적혀 있음** | 문구가 **광고처럼 보임** |
| 판정 | 문자열 정확 일치 (참/거짓) | 점수 ≥ 0.6 (확률) |
| 옵트인 토글 | 무관, 항상 동작 | **켜야만** 동작 (기본 OFF) |
| 실행 시점 | 화면 바뀔 때마다 즉시 | 스크롤 멈춘 뒤 600ms |
| 외부 전송 | 없음 | 카드 텍스트 (마스킹 후) |

중복은 구조적으로 막혀 있다. `CandidateExtractor.extract(root, exclude = confirmedRegions)`가 Layer 1이 잡은 영역과 조금이라도 겹치는 가지를 통째로 버리므로, **점선은 항상 "Layer 1이 못 찾은 것"만 가리킨다.**

점선이 틀릴 수 있다는 뜻을 UI로 구분해둔 것이다 — 실선/점선, "광고"/"광고 같아요".

### 광고 모두 닫기

화면 상단의 막대. 각 광고 영역 안에서 닫기 버튼처럼 생긴 노드를 찾아(깊이 25 이하, 영역과 교차, clickable, 72dp 이하) `ACTION_CLICK`을 보낸다.

이 막대는 테두리와 **별개 창**이다. 테두리 창은 `FLAG_NOT_TOUCHABLE`을 유지하고 막대 창만 터치를 받는다 — 한 창에 두면 플래그를 벗겨야 한다.

**못 찾았을 때 뒤로 가기로 폴백하면 안 된다.** 처음에 그렇게 만들었더니 웹 배너에는 접근성 트리에 X가 사실상 없어서 폴백이 매번 걸렸고, 광고가 아니라 보던 페이지가 닫혔다.

| 상황 | 동작 |
|---|---|
| 닫기 버튼을 찾음 | `ACTION_CLICK` |
| 못 찾았는데 **전면 광고** (높이 ≥ 화면의 75%) | `GLOBAL_ACTION_BACK` — 이때는 뒤로 가기가 곧 광고 닫기다 |
| 못 찾았고 배너 | **아무것도 안 함** + "이 광고는 닫기 버튼이 없어요" |

---

## 두 가지 모드

첫 실행 시 카드 두 개로 역할을 고른다 (`SetupActivity`).

| | 어르신 모드 👵 | 보호자 모드 👨‍👩‍👦 |
|---|---|---|
| 하는 일 | 접근성 서비스로 광고 감지·표시 | 이벤트 실시간 구독 + 대시보드 |
| 화면 | `MainActivity` (상태, AI 토글, 연결 코드) | `GuardianActivity` (이벤트 목록) |
| 접근성 권한 | 필요 | 불필요 |

어르신 화면의 **연결 코드**(ANDROID_ID 기반 userId)를 보호자가 입력하면 구독이 붙는다. 역할은 "모드 바꾸기"로 되돌릴 수 있다.

### Firebase 구조

```
users/{userId}      role("senior"|"guardian"), linkedTo(partnerId)
events/{userId}/{eventId}
                    timestamp, appPackage, adText, 
                    action("blocked"|"warned"|"ignored"), layer(1|2|3)
settings/{userId}   sensitivity(0.6), whitelist([])
```

Room에는 `ad_verdict` 캐시만 남긴다.

**광고 문구 자체는 올리지 않는다.** Layer 1·2 이벤트의 `adText`는 `"광고 N건 표시"`이고, `appPackage`에는 도메인 또는 패키지명까지만 들어간다(`hankyung.com`). 어르신이 무엇을 읽고 있었는지까지 보호자에게 넘길 이유가 없다. Layer 3은 누른 버튼 문구를 남기되 `CardText.mask()`로 전화·카드·주민번호를 지우고 120자로 자른다.

**같은 광고를 반복해서 알리지 않는다.** Layer 1·2는 스크롤할 때마다 같은 광고를 다시 표시하므로(실측 한 페이지 40회), `SightingLog`이 **출처+레이어 조합당 한 번만** 남긴다. 서비스가 사는 동안만 기억하고 128개를 넘기면 오래된 것부터 버린다.

---

## 파일 구조

```
app/src/main/java/com/senioradguard/
├── service/AdGuardAccessibilityService.kt  ← 단일 진입점, 레이어 배분
│                                             ScrollStopPredictor · SightingLog 포함
├── region/                     Layer 1 (팀원 AdDetectService에서 이식)
│   ├── AdLabelRules.kt         광고 라벨/컨테이너 id 판정 (순수 함수)
│   └── AdRegionScanner.kt      노드 트리 순회 → 광고 영역
│
├── agent/                      Layer 2 파이프라인
│   ├── CandidateExtractor.kt   Agent1: 노드트리 → 카드 단위 후보
│   ├── AdClassifier.kt         Agent2 인터페이스 ← 서버 교체점
│   ├── GeminiClassifier.kt       Gemini 구현 (기본)
│   ├── StubClassifier.kt         규칙 기반 폴백 (키 없을 때, LLM 아님)
│   ├── CrossValidator.kt       Agent4: viewId 약한 신호로 ±0.15
│   ├── AgentPipeline.kt        캐시 조회 → 판별 → 저장 → 표시
│   ├── CardText.kt             마스킹 · 정규화 · 캐시 키
│   └── RateLimiter.kt          시간당 호출 상한 (토큰버킷)
│
├── guard/                      Layer 3
│   ├── InstallGuard.kt         스토어 이동 · 설치 버튼 클릭 감지
│   └── InstallTriggerRules.kt  위험 문구 판정 (순수 함수)
│
├── overlay/
│   ├── AdBorderOverlay.kt      테두리(비터치) + 닫기 막대(터치, 별개 창)
│   └── OverlayManager.kt       Layer 3 차단 경고 팝업
│
├── remote/FirebaseRepo.kt      역할 저장 · 연결 · 이벤트 기록/구독
├── logger/AdEventLogger.kt     감지 이벤트 → Firebase
│
├── detector/                   블랙리스트 (보류 — 아래 참고)
│   ├── db/                     Room: ad_verdict
│   ├── BlacklistRepository.kt  원격 목록 다운로드/파싱/교체
│   ├── BlacklistUpdateWorker.kt  ⚠️ 예약 호출부 주석 처리됨
│   ├── DomainMatcher.kt        접미사 분해 O(1) 매칭
│   └── AdDetector.kt           ⚠️ dead code — 생성 지점 없음
│
├── ui/
│   ├── SetupActivity.kt        역할 선택 (어르신 / 보호자)
│   ├── GuardianActivity.kt     보호자 대시보드 + 연결 코드 입력
│   ├── ServiceStatus.kt        서비스가 실제로 살아있는지 확인
│   └── BatteryOptimizationGuide.kt
└── MainActivity.kt             어르신 화면 — 상태 · AI 토글 · 연결 코드
```

---

## 스캔 타이밍

예전에는 `TYPE_WINDOW_CONTENT_CHANGED`가 올 때마다 그 자리에서 트리 전체를 훑었다. 스크롤 한 번에 이벤트가 수십 개 오므로 그만큼 헛스캔이 났다. 지금은 **스크롤이 멎을 시점을 예측해 그때 한 번만** 훑는다.

- `TYPE_VIEW_SCROLLED`의 이동량과 도착 시각으로 px/ms 속도를 구하고, 플링의 지수 감쇠(`v = v₀·e^(-t/τ)`)로 남은 시간을 추정한다. **`VelocityTracker`는 쓸 수 없다** — MotionEvent를 먹는 물건인데 접근성 서비스는 MotionEvent를 받지 않는다.
- 이동량을 모르는 앱(크롬 등 `scrollY = -1`)에서는 **`null`로 구분해** 마지막 스크롤 이벤트 뒤 일정 시간을 기다린다. 이걸 `0`으로 뭉뚱그리면 "멈췄다"로 잘못 읽혀 스크롤 도중에 스캔이 나간다.
- 기기 주사율(`DisplayManager` → `Display.refreshRate`, **`DisplayMetrics`에는 없다**)로 프레임 간격을 구해 지연을 프레임 배수로 올림한다.
- **실행을 `Choreographer.postFrameCallback`에 붙이면 안 된다.** vsync 콜백은 **보이는 창이 있는 프로세스에만** 온다. 우리 앱이 백그라운드로 내려간 순간 콜백이 끊겨 감지가 통째로 멈춘다.
- 스크롤 중에는 표시를 걷어낸다. 스캔 없이 남기면 테두리가 제자리에 멈춰 엉뚱한 카드를 가리킨다.

**실기기 측정 (갤럭시 S24, 한경 본문 플링 8회)**

| | 값 |
|---|---|
| 화면 변경 이벤트 | 241개 |
| 예전 방식이었다면 스캔 | 241회 |
| 실제 스캔 | **13회 (94.6% 감소)** |
| 주사율 인식 | 120Hz → 8.33ms |

### 델타 스캔

`AdRegionScanner`에 부분 트리를 넘기는 방식은 쓸 수 없다. `scan(root)`가 첫 줄에서 `root.getBoundsInScreen()`으로 **화면 크기를 도출**하고 그 값이 `containerOf`(폭 70%, 높이 8~85%)·`adLinkOf`(50%) 비율 판정의 기준이라, 부분 트리를 넘기면 그 상수들의 의미가 바뀐다.

그래서 델타 식별은 Layer 2에서 한다. `AgentPipeline`이 카드 지문(`출처 + 텍스트 해시`)으로 이번 세션의 판정을 기억(LRU 256)해, 캐시만 보는 잦은 패스에서 **이미 판정한 카드는 DB 조회조차 건너뛴다.** 새 판별을 허용하는 유휴 패스에서는 기억을 건너뛰고 DB를 다시 읽어 만료·삭제를 반영한다.

---

## 감지 대상 앱

`targetApps` = 유튜브 / 인스타그램 / 당근 / 크롬. 스토어(Play, 갤럭시)는 Layer 3 전용.

**삼성 인터넷은 제외했다.** 렌더링된 웹 페이지를 접근성 트리에 노출하지 않는다 — 같은 URL에서 크롬은 노드 421개에 본문 텍스트가 나오는 반면 삼성 인터넷은 노드 20개(주소창·버튼 등 UI 껍데기)에 텍스트가 0개다. **다른 브라우저를 추가할 때도 이 방법으로 노출 여부를 먼저 확인할 것**, 그리고 `targetApps`와 `AdRegionScanner.browsers` **양쪽에** 넣어야 한다.

**감지 한계**

- 유튜브 인스트림 광고(영상 내부): 비디오 플레이어 안이라 접근 불가
- 네이버식 광고: 라벨이 `clickable=false` 노드에 있어 `adLinkOf`가 영역을 못 잡음
- 카카오톡·네이버 앱: 대상 목록에 없음 (프라이버시 범위를 좁히려는 의도적 선택)

---

## Layer 2 상세

```
스크롤 멈춤 600ms
   ↓
Agent1  CandidateExtractor   카드 단위 후보 (Layer 1이 잡은 영역은 제외)
   ↓
        캐시 조회 (Room)     히트면 판별 없이 즉시 표시
   ↓ 미스 (1회 최대 3건)
Agent2  AdClassifier         광고 여부 판정
   ↓
Agent4  CrossValidator       viewId 약한 신호로 ±0.15
   ↓
        저장 + 0.6 이상이면 점선 표시
```

**판별기는 Gemini다** (`GeminiClassifier`, 모델 `gemini-3.5-flash-lite`). 키가 없으면 규칙 기반 `StubClassifier`로 자동 폴백하므로 키 없는 팀원도 빌드·실행에 지장이 없다. 다만 스텁은 문맥을 이해하지 못하니 그 판정 품질을 LLM 품질로 보면 안 된다.

**모델명은 실제로 만료된다.** `gemini-2.0-flash`는 "no longer available", `gemini-2.5-flash`/`2.5-flash-lite`는 "no longer available **to new users**"로 404가 났다. **ListModels에 보이는 것과 이 키로 부를 수 있는 것은 다르다** — 목록만 보고 고르지 말고 실제로 한 번 호출해 확인할 것. `GeminiClassifier`의 주석에 확인용 curl이 있다.

응답은 `responseSchema`로 `{isAd, confidence, reason}` 스키마를 강제해 파싱 실패가 원천적으로 없다. 2.5 이상은 사고(thinking) 토큰이 출력 상한을 먼저 먹어 본문이 빈 채 `finishReason=MAX_TOKENS`가 올 수 있어 `maxOutputTokens=1024`로 여유를 뒀다.

**캐시 키** = `"$sourceKey|SHA-256(정규화 텍스트)"`. 정규화에서 숫자를 `#`으로 바꾸는 게 핵심이다 — 카운트다운·가격·조회수가 볼 때마다 달라서 그대로 두면 같은 광고인데 매번 미스가 난다. 부정 판정(`isAd=false`)도 저장하며 절감의 대부분이 여기서 나온다. TTL 30일.

**Agent4가 원 요구사항과 다른 이유**: HTML 클래스명 교차검증은 안드로이드 접근성 API로 불가능하다. 크롬이 노출하는 것은 HTML `id`뿐이고(`viewIdResourceName`), CSS `class`는 접근성 트리에 실리지 않는다. `AccessibilityNodeInfo.className`은 안드로이드 위젯 이름이라 무관하다. 그래서 id의 약한 신호로 대체했다. 신호가 **없을 때는 점수를 깎지 않는다** — 네이티브 앱 id는 대개 광고와 무관한 이름이라, 없다고 깎으면 앱 안의 진짜 광고가 전부 임계값 아래로 밀린다.

**부하·프라이버시 제어**: 유휴 600ms 후 실행 / 1회 최대 3건 / 시간당 60건 / 입력 400자 절단 / 전화·카드·주민번호 마스킹 / 입력 필드를 포함한 카드 제외 / **기본 OFF 옵트인**. Layer 1·3은 토글과 무관하게 항상 동작한다.

---

## 개발 환경 설정

### Gemini 키

`local.properties`에 한 줄 (이 파일은 `.gitignore`에 있다):

```
GEMINI_API_KEY=발급받은키
```

**⚠️ 이건 개발용 경로다.** APK는 누구나 뜯을 수 있어 문자열이 그대로 추출되고, 추출된 키로 남이 우리 할당량을 쓴다. 배포 전에는 우리 서버를 거치는 구현으로 반드시 교체할 것 — 바꿀 곳은 `AdClassifier` 구현체 하나뿐이다.

### Firebase

1. Firebase 콘솔에서 프로젝트 생성 → Android 앱 추가 (패키지명 `com.senioradguard`)
2. **빌드 → Realtime Database** 생성 (Firestore 아님)
3. `google-services.json`을 **`app/`** 에 넣는다 (`.gitignore` 대상 — 커밋하지 말 것)

**Realtime Database를 먼저 만들어야 한다.** DB가 없으면 `google-services.json`에 `firebase_url`이 들어가지 않고, SDK가 프로젝트 ID로 주소를 추측해 붙었다가 서버에 끊긴다. DB를 만든 뒤 **파일을 다시 다운로드**할 것.

**파일이 없어도 빌드된다.** `build.gradle.kts`가 파일 존재 여부로 `google-services` 플러그인 적용을 결정하고, `FirebaseRepo`의 모든 호출은 `FirebaseApp.getApps()`가 비어 있으면 조용히 no-op한다. 파일을 넣지 않은 팀원은 광고 감지만 되고 보호자 연동만 빠진 상태로 돌아간다.

### 설치

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
bash scripts/check_accessibility.sh     # 반드시 확인
```

---

## 현재 상태 (2026-08-13, 브랜치 `phase1-service-integration`)

세 레이어가 모두 실기기에서 동작하고, Firebase 이벤트 기록까지 실제로 확인했다. 남은 큰 항목은 보호자 폰 수신 검증(2대 필요), Firebase 보안 규칙, Vision(Agent3)이다.

### 실기기 검증 (갤럭시 S24, SM-S921N)

| 확인 항목 | 결과 |
|---|---|
| Layer 1 — 유튜브 쇼츠 광고, 크롬 광고 카드 테두리 | 동작 |
| Layer 2 — 후보 추출 → 캐시 → Gemini 판별 → 점선 표시 | 동작 (`캐시=2 판별=0 표시=1`로 재사용 확인) |
| Layer 2 — 쇼핑몰 자체 상품을 광고로 오판하는가 | **아니오.** LLM이 "제3자 광고가 아닌 정상 쇼핑 콘텐츠"로 구분 |
| Layer 2 — 기사 페이지 오탐 | 없음 |
| Layer 2 — 옵트인 OFF 시 | 파이프라인 자체가 돌지 않음 (로그 0줄) |
| Layer 3 — 스토어 이동 경고 / [뒤로가기] / [그냥 보기] | 모두 동작 |
| 백그라운드(크롬)에서 감지가 계속 도는가 | 동작 — 크롬 30초 체류 중 스캔 7회 |
| 광고 모두 닫기 — 웹 배너 | 페이지 유지 + "닫기 버튼이 없어요" 안내 |
| 역할 선택 → 어르신/보호자 화면 분기 | 동작 |
| **Firebase 이벤트 기록** | **동작** — Layer 3 스토어 이동, Layer 1 광고 표시 모두 도착 |
| **보호자 기록 중복 제거** | hankyung.com 스캔 40회 → 이벤트 1건, donga.com 이동 시 별도 1건 |
| 서비스 상태 표시 — 접근성 해제 시 경고 | 동작, 콜드 스타트 오탐 없음 |
| 스캔 최적화 — 플링 8회 | 이벤트 241개 → 스캔 13회 (94.6% 감소) |
| 메모리 (서비스만, 광고 페이지 25회 스크롤) | Java 27→30MB, Native 53→49MB — 누수 없음 |

**테스트**: 단위 91건 (`./gradlew testDebugUnitTest`), 계측 8건 (USB 실기기 필요).

### 검증에서 실제로 잡힌 결함들

기록해두는 이유는, 전부 코드를 읽어서는 안 보이고 실기기에서만 드러났기 때문이다.

| 증상 | 원인 |
|---|---|
| 광고 감지 순간 앱이 죽음 | 오버레이를 `applicationContext`로 붙임 → 창 토큰 없어 `BadTokenException`. 접근성 서비스 자신을 써야 한다 |
| 광고 감지 순간 앱이 죽음 (2) | `VIBRATE` 권한 누락 |
| 접근성 서비스가 통째로 죽음 | `Context.getDisplay()` — 서비스는 화면에 연결된 컨텍스트가 아니라 API 30+에서 예외. `DisplayManager`를 써야 한다 |
| 점선이 떴다가 곧바로 사라짐 | 콘텐츠 변경마다 AI 표시를 지우고 있었음. 쇼핑 페이지는 캐러셀 때문에 변경이 끊이지 않는다 |
| 같은 페이지가 캐시 키 두 개를 가짐 | 크롬이 스크롤 시 주소창을 트리에서 접어 도메인을 잃음 |
| 최적화가 오히려 스캔을 늘림 | 스크롤 이동량 "모름"을 `0`("안 움직임")으로 뭉뚱그림 |
| 유튜브 "광고 건너뛰기"에 차단 경고 | Layer 3 키워드에 "광고"가 있었음 — 광고를 피하려는 행동을 앱이 막는 꼴 |
| 보호자 알림이 조용히 아무 일도 안 함 | `AdEventLogger.init()` 호출부가 없었음 |
| 다른 앱을 보는 동안 감지가 통째로 멈춤 | 실행을 `Choreographer.postFrameCallback`에 붙였음. **보이는 창이 없는 프로세스에는 vsync가 오지 않는다.** A/B: 우리 앱 포그라운드 4회 → 크롬 20초 체류 **0회** |
| "광고 모두 닫기"가 광고 대신 페이지를 닫음 | X를 못 찾으면 `GLOBAL_ACTION_BACK`으로 폴백했는데, **웹 배너에는 X가 거의 없어** 폴백이 매번 걸렸다 |
| Layer 1·2 감지가 보호자에게 안 감 | `AdEventLogger.logAdMarked`를 만들고 **호출부를 넣지 않았음.** Layer 3 이벤트만 올라가고 있었다 |
| 자주 보는 사이트가 중복 기록됨 | `SightingLog`을 `containsKey`로 조회 — `LinkedHashMap`의 접근 순서는 **`get`/`put`만 갱신한다.** 단위 테스트가 잡음 |

### 두 레이어는 실제로 서로를 보완했다

한경은 광고에 AD 라벨이 붙어 Layer 1이 처리했고, 지마켓은 라벨이 없어 Layer 2만 반응했다.

**LLM 교체로 스텁의 오탐이 해결됐다.** 스텁은 지마켓 "슈퍼딜" 캐러셀을 "특가·무료배송·할인" 문구만 보고 광고로 표시했지만, 그건 쇼핑몰 자체 상품 영역이라 광고가 아니다. Gemini는 같은 카드를 이렇게 판정했다:

```
글자수=3765  isAd=false conf=0.90
  "쇼핑 앱 내에서 직접 상품을 탐색하고 있는 특가(슈퍼딜) 상품 목록이므로
   광고가 아닌 정상적인 쇼핑 콘텐츠입니다."

글자수=68    isAd=true  conf=0.95  → 점선 표시
  "최대 15% 쿠폰과 할인 행사를 홍보하여 상품 구매를 유도하는 제3자 광고성 콘텐츠"

글자수=321   isAd=false conf=1.00
  "쇼핑몰 하단 이용약관·사업자 정보·고객센터 등 필수 안내"
```

쇼핑몰 자체 콘텐츠와 제3자 광고를 구분하는 것이 판별의 핵심이고, 이건 문구 매칭으로는 안 되고 문맥 이해가 필요하다.

### 아직 안 되는 것

| 항목 | 상태 |
|---|---|
| **보호자 폰 수신** | DB 쓰기는 검증됐지만 대시보드가 실제로 그리는 것은 미확인. 기기 2대 필요 |
| **Layer 2 원격 기록** | 코드 경로는 Layer 1과 같은 함수를 쓰지만, AI 토글이 OFF라 실기기에서 미확인 |
| **웹 배너 닫기** | 접근성 트리에 X가 없어 닫을 수단 자체가 없다. 앱 내 광고·전면 광고에서는 동작 |
| **Agent3 (Vision)** | 이미지만 있는 광고 판별. 미착수 |
| **네이버식 광고** | 라벨이 `clickable=false` 노드에 있어 `adLinkOf`가 영역을 못 잡는다 |
| **삼성 인터넷** | 웹 콘텐츠를 접근성 트리에 노출하지 않아 우리 코드로는 해결 불가 |

### 보류 (추후 확장)

`detector/AdDetector.kt`는 **생성 지점이 없는 dead code**이고, 그래서 `BlacklistUpdateWorker`가 주 1회 14만 개 도메인을 받아 DB에 쓰는데 **읽는 코드가 없다.** 다운로드·파싱·DB 교체가 통째로 무거워 지금 단계에서는 쓰지 않기로 하고, `MainActivity`의 `schedule()` 호출부를 주석 처리했다(파일은 남김, `TODO Phase 2`).

**카카오 알림은 제거했다.** 비즈 앱 검수 승인이 끝내 나지 않아 메시지 API가 계속 거부됐고, 외부 승인에 기능이 묶여 있는 구조라 Firebase 구독으로 대체했다. 관련 코드·의존성·저장소 설정은 전부 삭제했다 (이력은 git에 남아 있다).

### 배포 전 반드시 할 것

1. **Gemini 키를 앱에서 빼기.** 지금은 `local.properties` → `BuildConfig`라 APK에서 추출된다. 우리 서버를 거치는 `AdClassifier` 구현으로 교체할 것
2. **Firebase 보안 규칙.** 현재 테스트 모드라 `userId`(ANDROID_ID)만 알면 누구나 남의 이벤트를 읽고 지울 수 있다. 실제로 개발 중 `curl` 한 줄로 DB 전체를 읽고 삭제했다. 인증 + 규칙이 필요하다
3. `SYSTEM_ALERT_WINDOW` / 접근성 권한의 사용 목적을 스토어 심사용으로 명시

---

## 실기기 테스트 시 반드시 지킬 것

**`am force-stop`이나 재설치를 하면 Android가 안전장치로 `enabled_accessibility_services` 설정을 지운다.** 항상 **설치/force-stop → 접근성 서비스 재활성화 → 앱 실행** 순서로 할 것. 반대로 하면 설정에 "활성화됨"으로 보여도 실제로는 이벤트를 받지 못한다. `scripts/redeploy.sh`가 이 순서를 지키고, `scripts/check_accessibility.sh`가 실제 바인드까지 확인한다.

그 외 함정들:

- **`uiautomator dump`는 다른 접근성 서비스를 꺼버린다** (`accessibility_enabled=0`). 진단하려던 서비스를 진단 도구가 죽인다.
- **삼성 One UI는 일반 앱의 `Log.d`/`Log.v`를 억제한다.** `Log.i` 이상을 쓸 것.
- **Jetpack Compose `Button`은 터치 클릭 시 `TYPE_VIEW_CLICKED`를 보내지 않는다.** Layer 3을 테스트하려면 네이티브 View/WebView로 재현할 것 (실제 광고 팝업은 대부분 네이티브라 로직 자체엔 문제 없음).
- Git Bash에서 `/sdcard/...` 경로는 MSYS가 변환해버린다. `MSYS_NO_PATHCONV=1`을 붙일 것.

---

## 주의사항

- **Layer 1·2 오버레이의 `FLAG_NOT_TOUCHABLE`은 절대 제거 금지.** 터치가 필요하면 닫기 막대처럼 창을 따로 만들 것
- 팀원 이식 코드(`AdLabelRules`, `AdRegionScanner`)의 **로직과 상수는 변경 금지**. 실기기에서 부딪혀 나온 대응이라 손대면 회귀한다
- API 키는 **채팅·이슈·커밋 어디에도 붙여넣지 말 것.** `local.properties`에만 둔다
- 제조사 절전(삼성 Freecess)이 서비스를 얼릴 수 있다 → 배터리 예외 안내 + 상태 표시로 대응 중

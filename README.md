# SeniorAdGuard — 프로젝트 컨텍스트

## 프로젝트 개요
노인 사용자가 스마트폰 사용 중 광고를 광고로 인식하지 못해 불필요한 앱을 설치하거나 개인정보를 입력하는 피해를 막기 위한 Android 앱.

**핵심 차별점 (경쟁사 대비)**
- AdGuard, Family Link 등 기존 서비스는 노인 특화 없음
- 앱 설치 팝업 감지 + 광고 클릭 직전 개입 → 기존에 없는 기능
- 보호자에게 카카오톡 알림 → 별도 앱 설치 불필요
- 노인 UX: 큰 글씨(24sp), 버튼 2개만, 빨간 경고

---

## 기술 스택
- 언어: Kotlin, UI는 Jetpack Compose (Material3)
- 화면 감지: Android AccessibilityService API
- 비동기: Kotlin Coroutines
- 보호자 알림: 카카오 SDK (메시지 API)
- 저장소: Room DB + WorkManager (블랙리스트 주 1회 갱신)
- Min SDK: API 26 (Android 8.0) / target·compile SDK 36

---

## 3-Layer 구조

`AdGuardAccessibilityService` 하나가 이벤트를 받아 루트 노드를 한 번만 가져오고 각 레이어에 배분한다.

| | 무엇을 잡나 | 표시 | 터치 |
|---|---|---|---|
| **Layer 1** | 공식 광고 표기(광고·Sponsored·AD)와 광고 네트워크 컨테이너 id | 주황 **실선** + "AD 광고" | 통과 |
| **Layer 2** | 라벨 없는 광고를 판별기로 추정 | 노랑 **점선** + "AI 광고 같아요" | 통과 |
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

중복은 구조적으로 막혀 있다. `CandidateExtractor.extract(root, exclude = confirmedRegions)`가 Layer 1이 잡은 영역과 조금이라도 겹치는 가지를 통째로 버리므로, **점선은 항상 "Layer 1이 못 찾은 것"만 가리킨다.** 한 광고에 실선과 점선이 겹치는 일은 없다.

점선이 틀릴 수 있다는 뜻을 UI로 구분해둔 것이다 — 실선/점선, "광고"/"광고 같아요".

---

## 파일 구조

```
app/src/main/java/com/senioradguard/
├── service/AdGuardAccessibilityService.kt  ← 단일 진입점, 레이어 배분
│
├── region/                     Layer 1 (팀원 AdDetectService에서 이식)
│   ├── AdLabelRules.kt         광고 라벨/컨테이너 id 판정 (순수 함수)
│   └── AdRegionScanner.kt      노드 트리 순회 → 광고 영역
│
├── agent/                      Layer 2 파이프라인
│   ├── CandidateExtractor.kt   Agent1: 노드트리 → 카드 단위 후보
│   ├── AdClassifier.kt         Agent2 인터페이스 ← LLM 교체점
│   ├── StubClassifier.kt         규칙 기반 임시 대역 (LLM 아님)
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
│   ├── AdBorderOverlay.kt      Layer 1·2 비차단 테두리 (한 창에 함께)
│   └── OverlayManager.kt       Layer 3 차단 경고 팝업
│
├── detector/                   블랙리스트 (현재 소비자 없음 — 아래 참고)
│   ├── db/                     Room: blacklist_domains, ad_verdict
│   ├── BlacklistRepository.kt  원격 목록 다운로드/파싱/교체
│   ├── BlacklistUpdateWorker.kt  주 1회 갱신
│   ├── DomainMatcher.kt        접미사 분해 O(1) 매칭
│   └── AdDetector.kt           ⚠️ dead code — 생성 지점 없음
│
├── notification/               카카오 보호자 알림 + 실패 큐
├── ui/
│   ├── ServiceStatus.kt        서비스가 실제로 살아있는지 확인
│   └── BatteryOptimizationGuide.kt
└── MainActivity.kt             상태 화면 + AI 판별 옵트인 토글
```

---

## 스캔 타이밍

예전에는 `TYPE_WINDOW_CONTENT_CHANGED`가 올 때마다 그 자리에서 트리 전체를 훑었다. 스크롤 한 번에 이벤트가 수십 개 오므로 그만큼 헛스캔이 났다.

지금은 **스크롤이 멎을 시점을 예측해 그때 한 번만** 훑는다.

- `TYPE_VIEW_SCROLLED`의 이동량과 도착 시각으로 px/ms 속도를 구하고, 플링의 지수 감쇠(`v = v₀·e^(-t/τ)`)로 남은 시간을 추정한다. **`VelocityTracker`는 쓸 수 없다** — MotionEvent를 먹는 물건인데 접근성 서비스는 MotionEvent를 받지 않는다.
- 이동량을 모르는 앱(크롬 등 `scrollY = -1`)에서는 **`null`로 구분해** 마지막 스크롤 이벤트 뒤 일정 시간을 기다린다. 이걸 `0`으로 뭉뚱그리면 "멈췄다"로 잘못 읽혀 스크롤 도중에 스캔이 나간다.
- 기기 주사율(`DisplayManager` → `Display.refreshRate`, **`DisplayMetrics`에는 없다**)로 프레임 간격을 구해 지연을 프레임 배수로 올림하고, 실제 실행은 `Choreographer.postFrameCallback`으로 vsync 직후에 붙인다.
- 스크롤 중에는 표시를 걷어낸다. 스캔 없이 남기면 테두리가 제자리에 멈춰 엉뚱한 카드를 가리킨다. (직전 영역을 스크롤 델타만큼 평행이동하는 방법도 검토했으나 고정 헤더·하단 배너가 어긋나서 버렸다.)

**실기기 측정 (갤럭시 S24, 한경 본문 플링 8회)**

| | 값 |
|---|---|
| 화면 변경 이벤트 | 241개 |
| 예전 방식이었다면 스캔 | 241회 |
| 실제 스캔 | **13회 (94.6% 감소)** |
| 주사율 인식 | 120Hz → 8.33ms |

### 델타 스캔

`AdRegionScanner`에 부분 트리를 넘기는 방식은 쓸 수 없다. `scan(root)`가 첫 줄에서 `root.getBoundsInScreen()`으로 **화면 크기를 도출**하고 그 값이 `containerOf`(폭 70%, 높이 8~85%)·`adLinkOf`(50%) 비율 판정의 기준이라, 부분 트리를 넘기면 그 상수들의 의미가 바뀐다.

그래서 델타 식별은 Layer 2에서 한다. `AgentPipeline`이 카드 지문(`출처 + 텍스트 해시`)으로 이번 세션의 판정을 기억(LRU 256)해, 캐시만 보는 잦은 패스에서 **이미 판정한 카드는 DB 조회조차 건너뛴다.** 지문이 텍스트 기반이라 같은 카드가 스크롤로 위치만 바뀌면 같은 키가 되고, 새로 들어온 카드만 자연히 DB·판별기로 넘어간다. 새 판별을 허용하는 유휴 패스에서는 기억을 건너뛰고 DB를 다시 읽어 만료·삭제를 반영한다.

---

## 감지 대상 앱

`targetApps` = 유튜브 / 인스타그램 / 당근 / 크롬. 스토어(Play, 갤럭시)는 Layer 3 전용.

**삼성 인터넷은 제외했다.** 렌더링된 웹 페이지를 접근성 트리에 노출하지 않는다 — 같은 URL에서 크롬은 노드 421개에 본문 텍스트가 나오는 반면 삼성 인터넷은 노드 20개(주소창·버튼 등 UI 껍데기)에 텍스트가 0개다. 볼 수 있는 정보가 없어 코드로는 해결이 불가능하다. **다른 브라우저를 추가할 때도 이 방법으로 노출 여부를 먼저 확인할 것**, 그리고 `targetApps`와 `AdRegionScanner.browsers` **양쪽에** 넣어야 한다.

## 감지 한계
- 유튜브 인스트림 광고(영상 내부): 비디오 플레이어 안이라 접근 불가
- 네이버식 광고: 라벨이 `clickable=false` 노드에 있어 `adLinkOf`가 영역을 못 잡음
- 카카오톡·네이버 앱: 대상 목록에 없음 (프라이버시 범위를 좁히려는 의도적 선택)

---

## Layer 2 상세 (현재 판별기는 스텁)

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

**판별기는 Gemini다** (`GeminiClassifier`, 모델 `gemini-3.5-flash-lite`). 키가 없으면 규칙 기반 `StubClassifier`로 자동 폴백하므로, 키가 없는 팀원도 빌드·실행에 지장이 없다. 다만 스텁은 문맥을 이해하지 못하니 그 판정 품질을 LLM 품질로 보면 안 된다.

키 설정 — `local.properties`에 한 줄 (이 파일은 `.gitignore`에 있다):

```
GEMINI_API_KEY=발급받은키
```

**⚠️ 이건 개발용 경로다.** APK는 누구나 뜯을 수 있어 문자열이 그대로 추출되고, 추출된 키로 남이 우리 할당량을 쓴다. 배포 전에는 우리 서버를 거치는 구현으로 반드시 교체할 것 — 바꿀 곳은 `AdClassifier` 구현체 하나뿐이다.

**모델명은 실제로 만료된다.** `gemini-2.0-flash`는 "no longer available", `gemini-2.5-flash`/`2.5-flash-lite`는 "no longer available **to new users**"로 404가 났다. **ListModels에 보이는 것과 이 키로 부를 수 있는 것은 다르다** — 목록만 보고 고르지 말고 실제로 한 번 호출해 확인할 것. `GeminiClassifier`의 주석에 확인용 curl이 있다.

응답은 `responseSchema`로 `{isAd, confidence, reason}` 스키마를 강제해 파싱 실패가 원천적으로 없다. 2.5 이상은 사고(thinking) 토큰이 출력 상한을 먼저 먹어 본문이 빈 채 `finishReason=MAX_TOKENS`가 올 수 있어 `maxOutputTokens=1024`로 여유를 뒀다.

**캐시 키** = `"$sourceKey|SHA-256(정규화 텍스트)"`. 정규화에서 숫자를 `#`으로 바꾸는 게 핵심이다 — 카운트다운·가격·조회수가 볼 때마다 달라서 그대로 두면 같은 광고인데 매번 미스가 난다. 부정 판정(`isAd=false`)도 저장하며 절감의 대부분이 여기서 나온다. TTL 30일.

**Agent4가 원 요구사항과 다른 이유**: HTML 클래스명 교차검증은 안드로이드 접근성 API로 불가능하다. 크롬이 노출하는 것은 HTML `id`뿐이고(`viewIdResourceName`), CSS `class`는 접근성 트리에 실리지 않는다. `AccessibilityNodeInfo.className`은 안드로이드 위젯 이름이라 무관하다. 그래서 id의 약한 신호로 대체했다. 신호가 **없을 때는 점수를 깎지 않는다** — 네이티브 앱 id는 대개 광고와 무관한 이름이라, 없다고 깎으면 앱 안의 진짜 광고가 전부 임계값 아래로 밀린다.

**부하·프라이버시 제어**: 유휴 600ms 후 실행 / 1회 최대 3건 / 시간당 60건 / 입력 400자 절단 / 전화·카드·주민번호 마스킹 / 입력 필드를 포함한 카드 제외 / **기본 OFF 옵트인**. Layer 1·3은 토글과 무관하게 항상 동작한다.

---

## 현재 상태 (2026-08-12, 브랜치 `phase1-service-integration`)

실기기(갤럭시 S24, SM-S921N)에서 검증한 것:

| 확인 항목 | 결과 |
|---|---|
| Layer 1 — 유튜브 쇼츠 광고, 크롬 광고 카드 테두리 | 동작 |
| Layer 2 — 후보 추출 → 캐시 → **Gemini 판별** → 점선 표시 | 동작 (`캐시=2 판별=0 표시=1`로 재사용 확인) |
| Layer 2 — 쇼핑몰 자체 상품을 광고로 오판하는가 | **아니오.** LLM이 "제3자 광고가 아닌 정상 쇼핑 콘텐츠"로 정확히 구분 |
| Layer 2 — 기사 페이지 오탐 | 없음 |
| Layer 2 — 옵트인 OFF 시 | 파이프라인 자체가 돌지 않음 |
| Layer 3 — 스토어 이동 경고 / 뒤로 가기 / 무시하기 | 모두 동작 |
| 서비스 상태 표시 — 접근성 해제 시 경고 | 동작, 콜드 스타트 오탐 없음 |
| DB version 1 → 2 덮어 설치 | 크래시 없음 |
| 메모리 (서비스만, 광고 페이지 25회 스크롤) | Java 27→30MB, Native 53→49MB — 누수 없음 |

단위 테스트 110건 통과 (`./gradlew testDebugUnitTest`).

두 레이어는 실제로 서로를 보완했다. 한경은 광고에 AD 라벨이 붙어 Layer 1이 처리했고, 지마켓은 라벨이 없어 Layer 2만 반응했다.

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

**아직 안 되는 것**
- **보호자 카카오톡 알림** — 코드 경로는 완성됐으나 카카오 비즈 앱 검수 승인 전이라 메시지 API가 거부된다. 실패분은 큐에 쌓이고 승인 후 첫 전송 성공 시점에 함께 재전송된다.
- **`SetupActivity` 흰 화면** — 카카오 로그인을 취소하면 빈 화면에 갇힌다.
- **Agent3 (Vision)** — 이미지만 있는 광고 판별. 미착수.

**보류 (추후 확장)**
- `detector/AdDetector.kt`는 **생성 지점이 없는 dead code**이고, 그래서 `BlacklistUpdateWorker`가 주 1회 14만 개 도메인을 받아 DB에 쓰는데 **읽는 코드가 없다.** 블랙리스트 방식 자체가 무거워(다운로드·파싱·DB 교체) 지금 단계에서는 쓰지 않기로 했다. 코드는 확장 방향으로 남겨둔다.

---

## 실기기 테스트 시 반드시 지킬 것

**`am force-stop`이나 재설치를 하면 Android가 안전장치로 `enabled_accessibility_services` 설정을 지운다.** 항상 **설치/force-stop → 접근성 서비스 재활성화 → 앱 실행** 순서로 할 것. 반대로 하면 설정에 "활성화됨"으로 보여도 실제로는 이벤트를 받지 못한다. `scripts/redeploy.sh`가 이 순서를 지킨다.

그 외 함정들:
- **`uiautomator dump`는 다른 접근성 서비스를 꺼버린다** (`accessibility_enabled=0`). 진단하려던 서비스를 진단 도구가 죽인다.
- **삼성 One UI는 일반 앱의 `Log.d`/`Log.v`를 억제한다.** `Log.i` 이상을 쓸 것.
- **Jetpack Compose `Button`은 터치 클릭 시 `TYPE_VIEW_CLICKED`를 보내지 않는다.** Layer 3을 테스트하려면 네이티브 View/WebView로 재현할 것 (실제 광고 팝업은 대부분 네이티브라 로직 자체엔 문제 없음).
- Git Bash에서 `/sdcard/...` 경로는 MSYS가 변환해버린다. `MSYS_NO_PATHCONV=1`을 붙일 것.

---

## 카카오 알림 — 남은 절차
1. 카카오 개발자 콘솔 → 앱 설정 → 비즈니스 → **비즈 앱 전환** (개인 비즈 앱 가능, 사업자등록번호 불필요)
2. **카카오톡 채널 연결** (비즈 앱 전환 시 필요할 수 있음)
3. 제품 설정에서 **친구 API/피커**, **메시지 API** 각각 사용 신청 → 검수 승인 대기
4. 승인 전에는 앱 소유자/팀 멤버 계정으로만 테스트된다

---

## 주의사항
- `SYSTEM_ALERT_WINDOW` 권한: Play Store 심사 시 접근성 목적 명시 필요
- 제조사 절전(삼성 Freecess)이 서비스를 얼릴 수 있다 → 배터리 예외 안내 + 상태 표시로 대응 중
- 오탐 방지: 표시 임계값 0.6으로 보수적 설정
- 팀원 이식 코드(`AdLabelRules`, `AdRegionScanner`)의 **로직과 상수는 변경 금지**. 실기기에서 부딪혀 나온 대응이라 손대면 회귀한다

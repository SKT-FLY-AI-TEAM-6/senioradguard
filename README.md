# GuArDian — 광고 감지 코어

노인 사용자가 스마트폰 사용 중 광고를 광고로 인식하지 못해 불필요한 앱을 설치하거나 개인정보를 입력하는 피해를 막기 위한 Android 앱.

**이 저장소는 [senioradguard](https://github.com/SKT-FLY-AI-TEAM-6/senioradguard)의 린 버전이다.** 감지 핵심 기능은 전부 그대로 살아 있고, **보호자 카카오톡 알림 연동만 걷어냈다** (아래 [§ 원본과 달라진 점](#원본과-달라진-점)).

**핵심 차별점 (경쟁사 대비)**
- AdGuard, Family Link 등 기존 서비스는 노인 특화 없음
- 앱 설치 팝업 감지 + 광고 클릭 직전 개입 → 기존에 없는 기능
- 노인 UX: 큰 글씨(24sp), 버튼 2개만, 빨간 경고

---

## 기술 스택
- 언어: Kotlin, UI는 Jetpack Compose (Material3)
- 화면 감지: Android AccessibilityService API
- 비동기: Kotlin Coroutines
- Layer 2 판별: Gemini (`HttpURLConnection` 직접 호출, 의존성 추가 없음)
- 저장소: Room DB + WorkManager (블랙리스트 주 1회 갱신)
- Min SDK: API 26 (Android 8.0) / target·compile SDK 36

---

## 3-Layer 구조

`GuardianAccessibilityService` 하나가 이벤트를 받아 루트 노드를 한 번만 가져오고 각 레이어에 배분한다.

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
app/src/main/java/com/guradian/
├── service/GuardianAccessibilityService.kt  ← 단일 진입점, 레이어 배분
│
├── region/                     Layer 1 (팀원 AdDetectService에서 이식)
│   ├── AdLabelRules.kt         광고 라벨/컨테이너 id 판정 (순수 함수)
│   └── AdRegionScanner.kt      노드 트리 순회 → 광고 영역
│
├── agent/                      Layer 2 파이프라인
│   ├── CandidateExtractor.kt   Agent1: 노드트리 → 카드 단위 후보
│   ├── AdClassifier.kt         Agent2 인터페이스 ← 판별기 교체점
│   ├── GeminiClassifier.kt       Gemini 직접 호출 (출처를 함께 넘긴다)
│   ├── StubClassifier.kt         키 없을 때의 규칙 기반 대역 (LLM 아님)
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
├── logger/AdEventLogger.kt     이벤트 기록 (전송 경로 없음 — 아래 참고)
├── ui/
│   ├── ServiceStatus.kt        서비스가 실제로 살아있는지 확인
│   └── BatteryOptimizationGuide.kt
└── MainActivity.kt             상태 화면 + AI 판별 옵트인 토글
```

---

## 원본과 달라진 점

**린 버전 = 감지 핵심 기능은 전부 유지, 카카오 연동만 제거.** 잘라낸 것은 원본 저장소(`phase2-gemini-classifier`)에 그대로 남아 있어 언제든 되가져올 수 있다.

| 제거한 것 | 사유 |
|---|---|
| `notification/KakaoNotifier.kt` · `PendingNotificationQueue.kt` | 카카오 비즈 앱 검수 미승인. 호출해도 메시지 API가 거부한다 |
| `ui/SetupActivity.kt` | 카카오 로그인 전용 화면. 로그인을 취소하면 흰 화면에 갇히는 버그도 있었다 |
| 카카오 SDK 의존성 · `devrepo.kakao.com` 저장소 · `KAKAO_NATIVE_APP_KEY` 주입 | 위와 한 몸 |
| `AuthCodeHandlerActivity` · `<queries>com.kakao.talk` · `POST_NOTIFICATIONS` | 위와 한 몸 |
| `AdEventLogger`의 **전송 경로** | 카카오 전송이 실체의 전부였다. 이벤트 기록(`Log.i`)만 남기고 호출부는 그대로 뒀다 — 나중에 알림이 붙을 때 이 구현만 바꾸면 된다 |

이름도 함께 바꿨다: 패키지 `com.senioradguard` → `com.guradian`, `AdGuardAccessibilityService` → `GuardianAccessibilityService`, `SeniorAdGuardApp` → `GuardianApp`. `applicationId`가 달라져 **원본 앱과 한 기기에 나란히 설치된다** — 실기기 3종으로 A/B를 볼 때 필요하다.

**감지 로직은 한 글자도 바뀌지 않았다.** `AdLabelRules` / `AdRegionScanner` / `CandidateExtractor` / `AgentPipeline` / `InstallTriggerRules` / `AdBorderOverlay`의 판정 규칙·상수·스크롤 추종 장치 전부 원본 그대로다.

### 2-Layer 재구축은 별도 브랜치

발표 아키텍처(2-Layer + 상시 액션바 + task 1~5 매핑)로 재구축하는 작업은 `integration/2layer` 브랜치에서 진행한다. main은 지금 동작하는 이 3-Layer 구조를 유지한다. 계획은 [`guardian-build-plan.md`](../guardian-build-plan.md) 참고.

---

## 감지 대상 앱

`targetApps` = 유튜브 / 인스타그램 / 당근 / 크롬. 스토어(Play, 갤럭시)는 Layer 3 전용.

**삼성 인터넷은 제외했다.** 렌더링된 웹 페이지를 접근성 트리에 노출하지 않는다 — 같은 URL에서 크롬은 노드 421개에 본문 텍스트가 나오는 반면 삼성 인터넷은 노드 20개(주소창·버튼 등 UI 껍데기)에 텍스트가 0개다. 볼 수 있는 정보가 없어 코드로는 해결이 불가능하다. **다른 브라우저를 추가할 때도 이 방법으로 노출 여부를 먼저 확인할 것**, 그리고 `targetApps`와 `AdRegionScanner.browsers` **양쪽에** 넣어야 한다.

## 감지 한계
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
Agent2  AdClassifier         광고 여부 판정 (Gemini, 키 없으면 Stub)
   ↓
Agent4  CrossValidator       viewId 약한 신호로 ±0.15
   ↓
        저장 + 0.6 이상이면 점선 표시
```

**판별기는 `local.properties`의 `GEMINI_API_KEY` 유무로 갈린다.** 키가 있으면 `GeminiClassifier`, 없으면 `StubClassifier`로 물러나 키 없이도 빌드·실행된다. 어느 쪽이 도는지는 로그 한 줄로 확인한다: `layer2 판별기=GEMINI:...` 또는 `layer2 판별기=STUB`.

**`StubClassifier`는 진짜 판별기가 아니다.** 파이프라인 전 구간을 실기기에서 돌려보기 위한 규칙 기반 대역이다. 문맥을 이해하지 못하므로 이 판정 품질을 LLM 품질로 오해하면 안 된다 (지마켓 "슈퍼딜" 오탐 사례가 아래에 있다).

**API 키를 앱에 두면 안 된다.** APK는 누구나 뜯을 수 있어 키가 그대로 추출된다. `local.properties` → `BuildConfig`는 개발 중에만 쓰고, 배포 전에는 우리 서버를 거치는 구현체로 교체해야 한다. `AdClassifier`가 그 교체점이고 거기 말고는 손댈 곳이 없다.

**캐시 키** = `"$sourceKey|SHA-256(정규화 텍스트)"`. 정규화에서 숫자를 `#`으로 바꾸는 게 핵심이다 — 카운트다운·가격·조회수가 볼 때마다 달라서 그대로 두면 같은 광고인데 매번 미스가 난다. 부정 판정(`isAd=false`)도 저장하며 절감의 대부분이 여기서 나온다. TTL 30일.

**Agent4가 원 요구사항과 다른 이유**: HTML 클래스명 교차검증은 안드로이드 접근성 API로 불가능하다. 크롬이 노출하는 것은 HTML `id`뿐이고(`viewIdResourceName`), CSS `class`는 접근성 트리에 실리지 않는다. `AccessibilityNodeInfo.className`은 안드로이드 위젯 이름이라 무관하다. 그래서 id의 약한 신호로 대체했다. 신호가 **없을 때는 점수를 깎지 않는다** — 네이티브 앱 id는 대개 광고와 무관한 이름이라, 없다고 깎으면 앱 안의 진짜 광고가 전부 임계값 아래로 밀린다.

**부하·프라이버시 제어**: 유휴 600ms 후 실행 / 1회 최대 3건 / 시간당 60건 / 입력 400자 절단 / 전화·카드·주민번호 마스킹 / 입력 필드를 포함한 카드 제외 / **기본 OFF 옵트인**. Layer 1·3은 토글과 무관하게 항상 동작한다. 429(무료 티어 상한)를 받으면 5분간 판별을 쉰다.

---

## 테두리가 스크롤을 따라오는 방법 — 두 개의 속도

트리 순회는 아무리 조여도 150~250ms가 걸린다. 여기에 스로틀이 얹히면 광고가 움직인 뒤 테두리가 따라오기까지 수백 ms인데, 60fps는 16.7ms다. **스캔 주기를 손보는 방식으로는 원리적으로 스무스해질 수 없다.** 그래서 두 갈래로 나눈다.

- **빠른 쪽 (매 스크롤 이벤트)** — 노드를 하나도 읽지 않고 이미 그려둔 테두리를 `scrollDeltaY`만큼 즉시 민다. `layoutParams`만 고치므로 프레임 단위로 붙는다.
- **느린 쪽 (스로틀된 스캔)** — 진짜 좌표를 찾아 추정치를 보정한다. 결과는 항상 몇백 ms 전의 화면이므로 `scrollSinceScanStart`만큼 되밀어 그린다. 이 보정이 없으면 스캔이 끝날 때마다 테두리가 뒤로 튄다.

느린 쪽을 제 시간에 끝나게 하는 장치가 다섯 개 있다. **하나라도 빠지면 테두리가 옛 자리에 얼어붙거나 깜빡인다.**

1. `TYPE_VIEW_SCROLLED` 구독 — 순수 스크롤에서는 `CONTENT_CHANGED`가 오지 않는다. 가장 큰 원인이었다
2. 트레일링 스로틀 — 겹친 요청을 버리지 않고 미룬다 (버리면 드래그의 마지막 위치를 잃는다)
3. 스캔 예산 — 순회 시간 상한 (`AdRegionScanner` 주석 참고)
4. 잘린 결과 홀드 — 최대 3회까지 직전 영역을 붙잡는다
5. 히스테리시스 — 나타날 때 즉시, 사라질 때 700ms 대기

**주사율 통일은 최종본 이후로 미뤄뒀다.** 실험 기기 3종의 주사율이 달라 지금 맞춰봐야 한 대 기준으로만 맞는다. 위 상수(`SCAN_INTERVAL_MS` 200 / `RECHECK_MS` 1000 / `CLEAR_DELAY_MS` 700 / `LAZY_RESCAN_MS` 600·1800·3500)는 그때까지 손대지 않는다.

---

## 현재 상태

단위 테스트 **74건 통과** (`./gradlew testDebugUnitTest`).

실기기(갤럭시 S24, SM-S921N)에서 원본으로 검증한 것 — 로직이 동일하므로 그대로 유효하다:

| 확인 항목 | 결과 |
|---|---|
| Layer 1 — 유튜브 쇼츠 광고, 크롬 광고 카드 테두리 | 동작 |
| Layer 1 — 스크롤 추종 (즉시 offset + 되밀기 보정) | 동작 |
| Layer 2 — 후보 추출 → 캐시 → 판별 → 점선 표시 | 동작 (gmarket에서 `캐시=1 판별=0`으로 재사용 확인) |
| Layer 2 — 기사 페이지 오탐 | 없음 (hankyung `후보=4 판별=3 표시=0`) |
| Layer 2 — 옵트인 OFF 시 | 파이프라인 자체가 돌지 않음 |
| Layer 2 — Gemini 표본 검증 | 12/12 (쇼핑몰 자체 특가·기사·앱 UI·대출·경품 미끼) |
| Layer 3 — 스토어 이동 경고 / 뒤로 가기 / 무시하기 | 모두 동작 |
| 서비스 상태 표시 — 접근성 해제 시 경고 | 동작, 콜드 스타트 오탐 없음 |
| 메모리 (서비스만, 광고 페이지 25회 스크롤) | Java 27→30MB, Native 53→49MB — 누수 없음 |

두 레이어는 실제로 서로를 보완했다. 한경은 광고에 AD 라벨이 붙어 Layer 1이 처리했고(Layer 2는 기사에 아무것도 표시하지 않음), 지마켓은 라벨이 없어 Layer 2만 반응했다.

**스텁이 지마켓 "슈퍼딜" 캐러셀을 광고로 잡은 것은 오탐이다** — 그 쇼핑몰 자체 상품 영역이다. "특가·무료배송·할인"만 보고 판단해서 생기는 전형적인 문맥 실패이고, `GeminiClassifier`가 출처(`sourceKey`)를 함께 넘겨 판정하는 이유가 이것이다.

**아직 안 되는 것**
- **Agent3 (Vision)** — 이미지만 있는 광고 판별. 미착수
- **보호자 알림 · Insight Report** — 이 저장소의 범위 밖

**정리 필요**
- `detector/AdDetector.kt`는 **생성 지점이 없는 dead code**다. 그 결과 `BlacklistUpdateWorker`가 주 1회 14만 개 도메인을 받아 DB에 쓰는데 **읽는 코드가 없다.** 나중에 쓸 자산이라 지우지 않았지만, 그때까지 워커를 멈출지 결정이 필요하다 (배터리는 이 앱이 삼성 절전에 얼어붙는 원인이기도 하다).

---

## 실기기 테스트 시 반드시 지킬 것

**`am force-stop`이나 재설치를 하면 Android가 안전장치로 `enabled_accessibility_services` 설정을 지운다.** 항상 **설치/force-stop → 접근성 서비스 재활성화 → 앱 실행** 순서로 할 것. 반대로 하면 설정에 "활성화됨"으로 보여도 실제로는 이벤트를 받지 못한다. `scripts/redeploy.sh`가 이 순서를 지킨다.

```bash
./scripts/redeploy.sh
adb logcat -s GurADian   # layer2 판별기=... / 후보·캐시·판별·표시 카운트
```

그 외 함정들:
- **`uiautomator dump`는 다른 접근성 서비스를 꺼버린다** (`accessibility_enabled=0`). 진단하려던 서비스를 진단 도구가 죽인다.
- **삼성 One UI는 일반 앱의 `Log.d`/`Log.v`를 억제한다.** `Log.i` 이상을 쓸 것.
- **Jetpack Compose `Button`은 터치 클릭 시 `TYPE_VIEW_CLICKED`를 보내지 않는다.** Layer 3을 테스트하려면 네이티브 View/WebView로 재현할 것 (실제 광고 팝업은 대부분 네이티브라 로직 자체엔 문제 없음).
- Git Bash에서 `/sdcard/...` 경로는 MSYS가 변환해버린다. `MSYS_NO_PATHCONV=1`을 붙일 것.

---

## 빌드

`local.properties`에 아래를 둔다 (버전 관리 제외).

```properties
sdk.dir=/path/to/Android/Sdk
GEMINI_API_KEY=...     # 없어도 빌드된다 — StubClassifier로 물러난다
```

---

## 주의사항
- `SYSTEM_ALERT_WINDOW` 권한: Play Store 심사 시 접근성 목적 명시 필요
- 제조사 절전(삼성 Freecess)이 서비스를 얼릴 수 있다 → 배터리 예외 안내 + 상태 표시로 대응 중
- 오탐 방지: 표시 임계값 0.6으로 보수적 설정
- 팀원 이식 코드(`AdLabelRules`, `AdRegionScanner`)의 **로직과 상수는 변경 금지**. 실기기에서 부딪혀 나온 대응이라 손대면 회귀한다

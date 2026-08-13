# GuArDian — 광고 감지 코어 (2-Layer 재구축)

> **이 브랜치는 `integration/2layer`다.** 발표 아키텍처(2-Layer + 상시 액션바)로
> 감지 코어를 재구축한 버전이고, **`main`은 건드리지 않는다.**
> `main`은 senioradguard에서 카카오 연동만 걷어낸 3-Layer 린 버전이 계속 돈다.

노인 사용자가 광고를 광고로 인식하지 못해 불필요한 앱을 설치하거나 개인정보를
입력하는 피해를 막기 위한 Android 앱.

---

## main과 무엇이 다른가

| | `main` (린 버전) | `integration/2layer` (이 브랜치) |
|---|---|---|
| 레이어 | 3-Layer (감지 / AI / 설치차단) | **2-Layer + 액션바** |
| Layer 2 실행 | 스크롤 멈춘 뒤 600ms 자동 | **[광고 찾기]를 누를 때만** |
| 대응 UI | 전체 화면 경고 팝업 (터치 가로챔) | **상시 액션바, 화면을 막지 않음** |
| 광고 닫기 | 없음 | 어포던스 탐지 → 대리 클릭 |
| 저장소 | Room | `VerdictStore` 인메모리 (task 4에서 교체) |
| 로그 | `AdEventLogger` | `DetectionLog` (원문이 못 들어가는 시그니처) |
| 악성 URL | 블랙리스트 dead code | `MaliciousUrlSource` (항상 false, 크롬 한정) |
| 의존성 | Room · WorkManager 포함 | **Kotlin + Compose + Coroutines만** |

**가장 큰 변화는 Layer 2의 트리거다.** 화면 텍스트가 외부로 나가는 유일한 지점이
사용자의 명시적 동작과 1:1로 대응하게 만들었다. 자동 실행 경로(`scheduleLayer2` /
`LAYER2_IDLE_MS`)는 아예 만들지 않았다.

---

## task 매핑 — Layer 3은 다섯 갈래로 흩어진다

이 재구축의 핵심이다. 원본 `guard/InstallGuard.kt` 하나가 하던 일이 이렇게 나뉜다.

| 원본 InstallGuard의 책임 | 새 위치 | task |
|---|---|---|
| `storePackages` 매칭 | `rule/EscapeRules` | **1** (룰 판정) |
| `InstallTriggerRules.isInstallTrigger()` | `rule/EscapeRules` | **1** (룰 판정) |
| `GLOBAL_ACTION_BACK` + HOME 폴백 | `action/EscapeAction` | **3** |
| `OverlayManager.showWarning()` (경고 UI) | `action/ActionBar` | **2** (액션바 공유) |
| `AdEventLogger` 기록 | `store/DetectionLog` | **4** (이 저장소 범위 밖) |
| `KakaoNotifier` 전송 | 없음 | **5** (이 저장소 범위 밖) |

**"Layer 3 = task 3"이 아니다.** 판정은 task 1로 내려가고, UI는 task 2가 만드는
액션바에 얹히고, 기록·알림은 이 저장소를 떠난다. task 3에 남는 것은 **탈출 동작
그 자체**뿐이다.

그래서 **task 3은 task 2 없이 완성될 수 없다.** 실제 순서는 task 2 → task 3이다.

### 저장소 범위

```
GuArDian = task 1 + task 2 + task 3
           (감지 · 닫기 · 탈출 — 전부 기기 안에서 끝나는 것)

범위 밖  = task 4 (DB · 서버 · 로그 집계)
           task 5 (가족 그룹 · Insight Report · 카카오)
```

범위 밖이라도 **붙일 자리는 남겼다.** `store/`의 인터페이스 셋이 그 이음매고,
**구현체는 전부 no-op이지만 호출부는 전부 배선돼 있다.** 호출부가 없으면 나중에
붙일 때 서비스를 다시 뜯어야 한다.

---

## 흐름

```
onAccessibilityEvent(event)
│
├─ Layer 1 (룰 판정) — 항상, 자동
│  ├─ RuleEngine.scan(root)        → List<Rect> → BorderTracker → 실선 테두리
│  ├─ RuleEngine.checkPackage(pkg) → Escape(STORE_REDIRECT)
│  ├─ RuleEngine.checkClick(text)  → Escape(INSTALL_TRIGGER)
│  └─ RuleEngine.checkUrl(root)    → Escape(MALICIOUS_URL)   ※ 크롬 한정, 지금은 항상 null
│
├─ Layer 2 (Agent) — [광고 찾기]를 누를 때만
│  └─ findAdsNow() → AgentPipeline → List<Rect> → 점선 테두리
│     (캐시만 보는 스크롤 경로는 유지 — 판별한 카드는 스크롤해도 점선이 따라온다)
│
└─ ActionBar (대응) — 주 버튼 하나
   우선순위: BUSY > [돌아가기] > [광고 닫기] > [광고 찾기] > 없음
```

## 파일 구조

```
app/src/main/java/com/guradian/
├── service/
│   └── GuardianAccessibilityService.kt   단일 진입점. 레이어 배분만
│
├── rule/                                 ← Layer 1 · task 1
│   ├── AdLabelRules.kt                   광고 라벨/컨테이너 id 판정 (순수 함수)
│   ├── AdRegionScanner.kt                노드 트리 순회 → 광고 영역
│   ├── EscapeRules.kt                    스토어 패키지 + 위험 문구 판정 (순수 함수)
│   ├── BrowserHost.kt                    크롬 주소창 → host
│   └── RuleEngine.kt                     위 넷의 단일 진입점
│
├── agent/                                ← Layer 2 · task 1
│   ├── AdCandidate.kt
│   ├── CandidateExtractor.kt             Agent1
│   ├── AdClassifier.kt                   인터페이스 (교체점)
│   ├── GeminiClassifier.kt               HttpURLConnection 직접 호출
│   ├── StubClassifier.kt                 키 없을 때 규칙 기반 대역
│   ├── CrossValidator.kt                 Agent4
│   ├── AgentPipeline.kt                  캐시 → 판별 → 표시
│   ├── CardText.kt                       마스킹 · 정규화 · 캐시 키
│   └── RateLimiter.kt                    토큰버킷
│
├── action/                               ← task 2 · task 3
│   ├── ActionBar.kt                      상시 액션바 (순수 View)
│   ├── ActionBarState.kt                 주 버튼 상태 머신 (순수 함수)
│   ├── CloseAffordanceFinder.kt          task 2 — 닫기·건너뛰기 탐지
│   └── EscapeAction.kt                   task 3 — BACK + HOME 폴백
│
├── overlay/
│   ├── AdBorderOverlay.kt                비차단 테두리 (실선/점선)
│   └── BorderTracker.kt                  스캔 주기 · 스크롤 추종 · 히스테리시스
│
├── store/                                ← 이음매 (task 4가 여기에 붙는다)
│   ├── VerdictStore.kt                   interface + InMemoryVerdictStore
│   ├── DetectionLog.kt                   interface + NoopDetectionLog
│   └── MaliciousUrlSource.kt             interface + EmptyMaliciousUrlSource
│
├── ui/
│   ├── ServiceStatus.kt
│   └── BatteryOptimizationGuide.kt
├── MainActivity.kt                       상태 화면 + AI 판별 토글
└── GuardianApp.kt                        Application
```

---

## 이음매 세 개

```kotlin
// store/VerdictStore.kt
interface VerdictStore {
    suspend fun get(key: String): Verdict?
    suspend fun put(key: String, verdict: Verdict)
}
// 지금: InMemoryVerdictStore(maxEntries = 500, ttlMillis = 30일)
// task 4: RoomVerdictStore

// store/DetectionLog.kt
interface DetectionLog {
    fun onAdDetected(source: String, count: Int, aiGuessed: Boolean)
    fun onAdClosed(source: String, succeeded: Boolean)
    fun onEscape(reason: EscapeReason, hostHash: String?)
}
// 지금: NoopDetectionLog (Log.i 한 줄만)
// task 4: RoomDetectionLog + 서버 전송 큐

// store/MaliciousUrlSource.kt
interface MaliciousUrlSource {
    suspend fun isMalicious(host: String): Boolean
}
// 지금: EmptyMaliciousUrlSource — 항상 false
// task 4: KISA 공공데이터포털 기반 구현
```

**`DetectionLog`에는 화면 텍스트 원문이 들어가지 않는다.** "보내지 않는다 — 화면
텍스트 원문 · URL 전문"을 문서가 아니라 **인터페이스 시그니처 단계에서 강제**한다.
`hostHash`는 `DetectionLog.hashHost()`가 만드는 SHA-256이다. 나중에 구현체를
잘못 만들어도 원문이 새어나갈 통로 자체가 없다.

**이 규칙을 깨는 파라미터를 추가하지 말 것.** 원문이 필요해 보이면 그건 집계
방식을 다시 생각해야 한다는 신호다.

---

## 액션바 — "큰 버튼 하나"

| 상황 | 주 버튼 | 색 | 동작 |
|---|---|---|---|
| 진행 중 | **찾는 중…** | 회색 | (비활성) |
| Escape 상태 | **돌아가기** | 빨강 | BACK → 안 바뀌면 HOME |
| 광고 테두리 있음 | **광고 닫기** | 주황 | 어포던스 탐지 → `ACTION_CLICK` |
| AI 판별 ON | **광고 찾기** | 파랑 | Layer 2 1회 실행 |
| 그 외 | 없음 | | |

어르신에게 버튼 세 개를 동시에 주면 고르는 일 자체가 부담이다. 지금 상황에서 가장
필요한 하나만 크게 보여준다. `busy`가 전부를 이기는 이유는, 손 밑에서 버튼이 다른
기능으로 바뀌면 두 번째 누름이 엉뚱한 동작을 실행하기 때문이다.

### 구현 제약 (반드시 지킬 것)

- `TYPE_ACCESSIBILITY_OVERLAY` + **서비스 컨텍스트**(`this`). `applicationContext`를
  쓰면 창 토큰이 없어 `BadTokenException`으로 죽는다
- **Compose 아님, 순수 View.** 오버레이 창에는 `ViewTreeLifecycleOwner`·
  `SavedStateRegistryOwner`가 없다
- 테두리 창(`FLAG_NOT_TOUCHABLE`)과 **별도 창**이어야 한다. 합치면 둘 다 터치를
  받거나 둘 다 통과시킨다
- 터치 타깃 72dp, 글자 24sp 이상
- **광고 위에 겹치지 않는다** — 하단 바가 광고 `Rect`와 겹치면 상단으로 옮긴다.
  광고를 가리면 구글 정책 위반

### `ACTION_CLICK` 정책

**대리 클릭은 사용자가 `[광고 닫기]`를 누른 경우에만 한다.** 자동으로 닫으면 그건
광고 차단이고 정책 위반이다. 우리가 하는 일은 "닫기 버튼이 저기 있는데 너무 작아서
못 누르는 사람 대신 눌러주는 것"이지 "광고를 없애는 것"이 아니다.
**호출부는 액션바 클릭 핸들러 하나뿐이어야 한다.**

---

## 스크롤 추종 — 두 개의 속도

트리 순회는 아무리 조여도 150~250ms가 걸린다. 60fps는 16.7ms다. **스캔 주기를
손보는 방식으로는 원리적으로 스무스해질 수 없다.** 그래서 두 갈래로 나눈다.

- **빠른 쪽 (매 스크롤 이벤트)** — 노드를 하나도 읽지 않고 이미 그려둔 테두리를
  `scrollDeltaY`만큼 즉시 민다. `layoutParams`만 고치므로 프레임 단위로 붙는다.
- **느린 쪽 (스로틀된 스캔)** — 진짜 좌표를 찾아 보정한다. 결과는 몇백 ms 전의
  화면이므로 `scrollSinceScanStart`만큼 되밀어 그린다. 이 보정이 없으면 스캔이
  끝날 때마다 테두리가 뒤로 튄다.

느린 쪽을 제 시간에 끝나게 하는 장치 다섯 개. **하나라도 빠지면 얼어붙거나 깜빡인다.**

1. `TYPE_VIEW_SCROLLED` 구독 — 순수 스크롤에서는 `CONTENT_CHANGED`가 오지 않는다
2. 트레일링 스로틀 — 겹친 요청을 버리지 않고 미룬다
3. 스캔 예산 — 순회 시간 상한
4. 잘린 결과 홀드 — 최대 3회
5. 히스테리시스 — 나타날 때 즉시, 사라질 때 700ms 대기

원본에서는 이게 전부 서비스 안에 흩어져 있었다. `overlay/BorderTracker.kt` 한
클래스로 뽑았고 **로직과 상수는 한 글자도 바꾸지 않았다.**

**주사율 통일은 최종본 이후로 미뤘다.** 실험 기기 3종의 주사율이 달라 지금
맞춰봐야 한 대 기준으로만 맞는다. `SCAN_INTERVAL_MS` 200 / `RECHECK_MS` 1000 /
`CLEAR_DELAY_MS` 700 / `MAX_TRUNCATED_HOLDS` 3 / `LAZY_RESCAN_MS` 600·1800·3500은
그때까지 손대지 않는다.

---

## 감지 한계

- **유튜브 인스트림 광고**(영상 내부): 비디오 플레이어 안이라 접근성 트리에 없다.
  **이 설계로 해결되지 않는 구조적 한계다.**
- **네이버식 광고**: 라벨이 `clickable=false` 노드에 있어 `adLinkOf`가 영역을 못 잡는다
- **삼성 인터넷**: 렌더링된 웹 페이지를 접근성 트리에 노출하지 않는다 — 같은 URL에서
  크롬은 노드 421개에 본문 텍스트가 나오는 반면 삼성 인터넷은 노드 20개에 텍스트가
  0개다. 볼 정보가 없어 코드로는 해결 불가라 `targetApps`에서 뺐다
- **악성 URL은 크롬 한정**: 인스타·유튜브·당근에는 URL이라는 개념이 접근성 트리에
  없다. 구현체를 아무리 좋게 만들어도 이 한계는 사라지지 않는다
- **카카오톡·네이버 앱**: 대상 목록에 없음 (프라이버시 범위를 좁히려는 의도적 선택)

---

## 빌드와 검증

```properties
# local.properties (버전 관리 제외)
sdk.dir=/path/to/Android/Sdk
GEMINI_API_KEY=...     # 없어도 빌드된다 — StubClassifier로 물러난다
```

```bash
./gradlew testDebugUnitTest    # 단위 테스트 97건
./scripts/redeploy.sh          # 실기기 배포 (접근성 재활성화 순서 포함)
adb logcat -s GurADian
```

실기기 검증 항목은 [`docs/실기기-검증-체크리스트.md`](docs/실기기-검증-체크리스트.md).

**`GEMINI_API_KEY`가 없으면 `StubClassifier`로 떨어진다.** 스텁은 문맥을 못 읽어
지마켓 "슈퍼딜"(그 쇼핑몰 자체 상품 영역)을 광고로 오탐한 전례가 있고, 버튼을 눌러
부르는 구조에서는 사용자가 결과를 기다리므로 오탐이 그대로 평가받는다.
`layer2 판별기=` 로그로 어느 쪽이 도는지 먼저 확인할 것.

---

## 브랜치

```
main (린 버전 — 3-Layer, 카카오만 제거)   ← 건드리지 않는다
 │
 └── feat/2layer-scaffold                  재구축 뼈대 (region 주석)
      └── feat/layer1-rule                 rule/ · overlay/ · MaliciousUrlSource
           └── feat/layer2-agent           agent/ · VerdictStore · findAdsNow()
                └── feat/action-bar        action/ · DetectionLog
                                  ↓
                        integration/2layer  (A → B → C 순서로 병합)
```

계획서 §5는 세 브랜치를 main에서 나란히 띄우는 그림이었지만, 실제로는 B가 A의
`BrowserHost`를, C가 A의 `confirmedRegions`와 B의 `findAdsNow()`를 소비하므로
그 순서대로 쌓았다. 계획서 §5.1이 지정한 병합 순서(A → B → C)와 같다.

## 남은 항목

- 어포던스 탐지 휴리스틱 — 앱별 실기기 튜닝 (지금은 인터페이스·배선까지)
- 주사율 통일 — 실험 기기 3종, 최종본 이후
- KISA 악성 URL 소스 — 공공데이터포털 API 신청·포맷
- task 4·5 백엔드 (FastAPI + PostgreSQL) 착수 시점
- Agent3 (Vision) — 이미지만 있는 광고 판별. 미착수

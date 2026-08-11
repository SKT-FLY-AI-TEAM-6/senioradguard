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

**`StubClassifier`는 진짜 판별기가 아니다.** LLM 키 없이 파이프라인 전 구간을 실기기에서 돌려보기 위한 규칙 기반 대역이다. 문맥을 이해하지 못하므로 이 판정 품질을 LLM 품질로 오해하면 안 된다. LLM을 붙일 때는 `AdClassifier` 구현체 하나만 갈아끼우면 되고, 나머지(캐시·오버레이·상한)는 손대지 않는다.

**API 키를 앱에 두면 안 된다.** APK는 누구나 뜯을 수 있어 키가 그대로 추출된다. `local.properties` → `BuildConfig`는 개발 중에만 쓰고, 배포 전에는 우리 서버를 거치는 구현체로 교체해야 한다. `AdClassifier`가 그 교체점이다.

**캐시 키** = `"$sourceKey|SHA-256(정규화 텍스트)"`. 정규화에서 숫자를 `#`으로 바꾸는 게 핵심이다 — 카운트다운·가격·조회수가 볼 때마다 달라서 그대로 두면 같은 광고인데 매번 미스가 난다. 부정 판정(`isAd=false`)도 저장하며 절감의 대부분이 여기서 나온다. TTL 30일.

**Agent4가 원 요구사항과 다른 이유**: HTML 클래스명 교차검증은 안드로이드 접근성 API로 불가능하다. 크롬이 노출하는 것은 HTML `id`뿐이고(`viewIdResourceName`), CSS `class`는 접근성 트리에 실리지 않는다. `AccessibilityNodeInfo.className`은 안드로이드 위젯 이름이라 무관하다. 그래서 id의 약한 신호로 대체했다. 신호가 **없을 때는 점수를 깎지 않는다** — 네이티브 앱 id는 대개 광고와 무관한 이름이라, 없다고 깎으면 앱 안의 진짜 광고가 전부 임계값 아래로 밀린다.

**부하·프라이버시 제어**: 유휴 600ms 후 실행 / 1회 최대 3건 / 시간당 60건 / 입력 400자 절단 / 전화·카드·주민번호 마스킹 / 입력 필드를 포함한 카드 제외 / **기본 OFF 옵트인**. Layer 1·3은 토글과 무관하게 항상 동작한다.

---

## 현재 상태 (2026-08-12, 브랜치 `phase1-service-integration`)

실기기(갤럭시 S24, SM-S921N)에서 검증한 것:

| 확인 항목 | 결과 |
|---|---|
| Layer 1 — 유튜브 쇼츠 광고, 크롬 광고 카드 테두리 | 동작 |
| Layer 2 — 후보 추출 → 캐시 → 판별 → 점선 표시 | 동작 (gmarket에서 `캐시=1 판별=0`으로 재사용 확인) |
| Layer 2 — 기사 페이지 오탐 | 없음 (hankyung `표시=0`) |
| Layer 2 — 옵트인 OFF 시 | 파이프라인 자체가 돌지 않음 |
| Layer 3 — 스토어 이동 경고 / 뒤로 가기 / 무시하기 | 모두 동작 |
| 서비스 상태 표시 — 접근성 해제 시 경고 | 동작, 콜드 스타트 오탐 없음 |
| DB version 1 → 2 덮어 설치 | 크래시 없음 |
| 메모리 (서비스만, 광고 페이지 25회 스크롤) | Java 27→30MB, Native 53→49MB — 누수 없음 |

단위 테스트 110건 통과 (`./gradlew testDebugUnitTest`).

**아직 안 되는 것**
- **보호자 카카오톡 알림** — 코드 경로는 완성됐으나 카카오 비즈 앱 검수 승인 전이라 메시지 API가 거부된다. 실패분은 큐에 쌓이고 승인 후 첫 전송 성공 시점에 함께 재전송된다.
- **`SetupActivity` 흰 화면** — 카카오 로그인을 취소하면 빈 화면에 갇힌다.
- **Agent3 (Vision)** — 이미지만 있는 광고 판별. 미착수.

**정리 필요**
- `detector/AdDetector.kt`는 **생성 지점이 없는 dead code**다. 그 결과 `BlacklistUpdateWorker`가 주 1회 14만 개 도메인을 받아 DB에 쓰는데 **읽는 코드가 없다.** Phase 2에서 쓸 자산이라 지우지 않았지만, 그때까지 워커를 멈출지 결정이 필요하다 (배터리는 이 앱이 삼성 절전에 얼어붙는 원인이기도 하다).

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

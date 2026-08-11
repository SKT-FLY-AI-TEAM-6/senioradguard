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
- 언어: Kotlin
- 화면 감지: Android AccessibilityService API
- AI 분석: Google ML Kit Text Recognition (한국어)
- 비동기: Kotlin Coroutines 1.11.0
- 보호자 알림: 카카오 SDK 2.24.0 (메시지 API)
- 블랙리스트: Room DB (로컬) + WorkManager (주 1회 업데이트)
- Min SDK: API 26 (Android 8.0)

---

## 파일 구조

```
app/src/main/
├── java/com/senioradguard/
│   ├── service/
│   │   └── AdGuardAccessibilityService.kt  ← 핵심: 이벤트 감지
│   ├── detector/
│   │   └── AdDetector.kt                   ← 3단계 탐지 로직
│   ├── overlay/
│   │   └── OverlayManager.kt               ← 경고 UI (노인용)
│   ├── notification/
│   │   └── KakaoNotifier.kt                ← 카카오 보호자 알림
│   ├── logger/
│   │   └── AdEventLogger.kt                ← 차단 내역 로깅
│   └── ui/
│       ├── MainActivity.kt                 ← 앱 진입점
│       └── SetupActivity.kt                ← 최초 설정 (카카오 로그인 + 보호자 선택)
└── res/
    └── xml/
        └── accessibility_service_config.xml
```

---

## 핵심 컴포넌트 설명

### AdGuardAccessibilityService
항상 백그라운드 실행. 3가지 이벤트 감지:
- `TYPE_WINDOW_STATE_CHANGED` → Play Store / Galaxy Store 강제 이동 감지
- `TYPE_WINDOW_CONTENT_CHANGED` → 팝업/광고 배너 텍스트 분석
- `TYPE_VIEW_CLICKED` → "설치하기" 등 버튼 클릭 직후 경고 (50ms 딜레이)

### AdDetector — 3단계 파이프라인
```
① 키워드 패턴 (< 1ms, 항상)     → 점수 × 0.4
② 도메인 블랙리스트 (< 5ms)     → 점수 × 0.6
③ ML Kit AI 이미지 분석 (< 200ms, 의심 시만)
최종 점수 0.6 이상 → 경고 표시
```

### KakaoNotifier (보호자 알림)
- 카카오 메시지 API v1 사용
- 최초 설정: 노인 카카오 로그인 → 친구 피커에서 보호자 선택 → UUID 저장
- 광고 감지 시 보호자 카카오톡으로 즉시 메시지 전송
- 보호자는 카카오톡만 있으면 됨 (별도 앱 불필요)
- Access Token 만료 시 Refresh Token으로 자동 갱신

### OverlayManager
- `SYSTEM_ALERT_WINDOW` 권한으로 모든 앱 위에 표시
- 버튼: "뒤로 가기"(빨간, 크게) / "무시하기"(회색, 작게)
- "뒤로 가기" 클릭 시 `performGlobalAction(GLOBAL_ACTION_BACK)` 실행

---

## 감지 한계
- 인스타그램 피드 Sponsored 게시물: 자체 렌더링 엔진으로 감지 어려움
- 유튜브 인스트림 광고(영상): 비디오 플레이어 내부라 접근 불가
- 일반 팝업/설치 유도 버튼: 감지 가능

---

## 현재 상태 (2026-08-09)
- build.gradle.kts 의존성 추가 완료
- 파일 복사 완료 (service, detector, overlay, notification, logger, ui)
- AndroidManifest.xml 설정 완료
- Gradle sync 완료
- 빌드 에러 전부 해결 (compileDebugKotlin BUILD SUCCESSFUL)
- Room DB 블랙리스트 + WorkManager 주 1회 업데이트 구현 완료 (Phase 2)
  - `detector/db/BlacklistDomain.kt`, `BlacklistDao.kt`, `AppDatabase.kt`
  - `detector/BlacklistRepository.kt` — DB 캐시 조회 + 원격 목록 다운로드/교체
  - `detector/BlacklistUpdateWorker.kt` — `PeriodicWorkRequest`(7일), `MainActivity.onCreate`에서 스케줄
  - `AdDetector.scoreByBlacklist()`가 suspend로 변경, 메모리 캐시 + Room 백업
  - `REMOTE_BLACKLIST_URL`은 플레이스홀더(`example.com`) — 실제 배포 전 자체 서버 URL로 교체 필요
- 카카오 SDK 연동 완료
  - 네이티브 앱 키는 `local.properties`의 `KAKAO_NATIVE_APP_KEY`에 저장(버전 관리 제외), `build.gradle.kts`가 `BuildConfig.KAKAO_NATIVE_APP_KEY` / `manifestPlaceholders`로 노출
  - `SeniorAdGuardApp`(Application) 신규 작성 → `onCreate`에서 `KakaoSdk.init()` 호출, Manifest `android:name=".SeniorAdGuardApp"` 등록
  - Manifest에 `<queries>com.kakao.talk</queries>`(카카오톡 설치 여부 확인용), `AuthCodeHandlerActivity` 리다이렉트 scheme(`kakao${'$'}{KAKAO_NATIVE_APP_KEY}`) 추가
  - `KakaoNotifier.refreshAccessToken()`의 하드코딩 키 리터럴 제거 → `BuildConfig.KAKAO_NATIVE_APP_KEY` 참조로 교체
  - `MainActivity`: 최초 실행(보호자 미설정) 시 `SetupActivity`로 자동 이동하도록 연결
  - `SetupActivity`: `AppCompatActivity` 상속 시 앱 테마(AppCompat 계열 아님)와 불일치로 크래시 나던 버그 수정 → `ComponentActivity`로 교체

### 에뮬레이터 실행 검증 결과 (2026-08-10)
- 로그인은 정상 동작: 실제 카카오 계정 동의 화면까지 도달, 로그인 성공 후 앱으로 정상 복귀
- **친구 피커(`PickerClient.selectFriend`)에서 막힘**: 콜백이 아예 호출되지 않음(성공/실패 모두 로그 없음), 시스템에 `Toast already killed`만 기록됨
- 원인: 카카오 정책상 **친구 API/피커/메시지 API는 "비즈 앱 전환" + "사용 신청(검수) 승인"이 필요** — 동의항목만 켜져있고 비즈 앱 미전환 상태라 API 호출 자체가 거부됨 (검수 승인 전에는 팀 멤버로 등록된 계정만 테스트 가능)
- **KakaoNotifier의 메시지 API도 동일 정책 대상** — 친구 API뿐 아니라 메시지 API도 비즈 앱 전환 + 별도 사용 신청 필요

### 에뮬레이터 기본 동작 테스트 결과 (2026-08-10)
카카오 알림은 제외하고 핵심 감지 파이프라인만 에뮬레이터에서 실제로 검증함 (`AccessibilityService` 활성화 + `SYSTEM_ALERT_WINDOW` 권한 부여 후 테스트):
- Play Store 강제 이동 감지 (`TYPE_WINDOW_STATE_CHANGED`) — 정상 동작, 오버레이 정상 표시
- 설치 유도 버튼 클릭 감지 (`TYPE_VIEW_CLICKED`) — 정상 동작 (단, **Jetpack Compose `Button`은 터치 클릭 시 `TYPE_VIEW_CLICKED` 이벤트를 보내지 않는 Compose 자체의 접근성 특성**이 있어 네이티브 `View`로 재현해서 확인함 — 실제 광고 팝업 대부분이 네이티브 View/WebView라 서비스 로직 자체엔 문제 없음)
- "뒤로 가기" 오버레이 버튼 → `performGlobalAction(GLOBAL_ACTION_BACK)` 정상 동작
- **팝업/배너 텍스트 스캔 (`TYPE_WINDOW_CONTENT_CHANGED` → `analyzeWindowContent`)에서 버그 발견 및 수정**: 키워드+블랙리스트 URL이 완벽히 매칭돼도(`combinedScore=1.0`) `aiScore`가 항상 0이라 `finalScore`가 정확히 0.6이 되는데, 기존 조건이 `finalScore > 0.6f`(초과)라서 최상의 케이스에서도 절대 오버레이가 뜨지 않는 상태였음. `AdGuardAccessibilityService.kt:124`를 `finalScore >= 0.6f`로 수정해 해결.

**테스트 중 확인한 환경 특이사항 (에뮬레이터 조작 시 주의)**: `adb shell am force-stop <pkg>`를 실행하면 Android가 안전장치로 해당 앱의 `enabled_accessibility_services` 설정을 자동으로 지워버림. 재설치(`installDebug`) 시에도 마찬가지로 초기화됨. 따라서 테스트 순서는 항상 **force-stop(또는 재설치) → 접근성 서비스 재활성화(`settings put secure enabled_accessibility_services ...`) → 앱 실행** 순으로 해야 함 (반대로 하면 서비스가 "활성화됨"으로 보여도 실제로 이벤트를 받지 못함).

## MediaProjection(AI 이미지 분석) 연동 완료 (2026-08-10)
트리거 기반으로 실제 구현 완료, 에뮬레이터에서 전체 파이프라인 실동작 검증함.

**구조**
- `MainActivity.kt` — 앱 시작 시(진짜 최초 `onCreate`, `savedInstanceState == null`일 때만) `MediaProjectionManager.createScreenCaptureIntent()`로 권한 1회 요청. 승인되면 `ScreenCaptureService`를 포그라운드 서비스로 기동.
- `detector/ScreenCaptureService.kt` (신규) — MediaProjection을 열고 화면 크기에 맞는 `ImageReader` + `VirtualDisplay`를 만들어 **대기 상태로 유지**만 함 (지속적으로 프레임을 디코딩하지 않음). Android 10+ 는 MediaProjection에 포그라운드 서비스가 필수이고 Android 14+ 는 `foregroundServiceType="mediaProjection"` 선언 + `startForeground()`를 `getMediaProjection()`보다 먼저 호출해야 해서 이 구조가 필요했음.
- `detector/ScreenCaptureHelper.kt` — `attach()/detach()`로 위 리소스를 등록받고, `getLatestFrame()` 호출 시점에만 `ImageReader.acquireLatestImage()`로 동기 캡처 + Bitmap 변환 (풀 방식 — 진짜 필요할 때만 캡처/디코딩 비용 발생).
- `service/AdGuardAccessibilityService.kt` — `analyzeWindowContent()`의 진입 조건을 `combinedScore > 0.5f || keywordScore >= 0.4f`로 확장(중요: `combinedScore`는 URL 매칭 없이는 절대 0.5를 못 넘으므로 이 OR 조건이 없으면 순수 키워드 케이스가 AI 재검사 단계에 아예 도달 못함). 그 안에서 `keywordScore >= 0.4f`일 때만 실제로 `captureScreen()` 호출. 캡처된 Bitmap은 OCR 후 `recycle()`로 즉시 해제.
- `detector/AdDetector.kt`의 `scoreByAI()`는 이미 ML Kit Korean OCR로 올바르게 구현되어 있었음 — 문제는 항상 `null` 스크린샷을 받는 호출부였고, 이번 수정으로 실제 Bitmap이 들어가면서 정상 동작.

**Manifest 변경**: `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS` 권한 추가, `ScreenCaptureService`를 `foregroundServiceType="mediaProjection"`으로 선언.

**에뮬레이터 실동작 검증 결과**: 권한 다이얼로그에서 "Share entire screen" 선택 → 실제 1080x2400 Bitmap 캡처 확인 → ML Kit OCR이 화면 속 "설치하기 지금 설치" 텍스트를 정확히 읽어 `scoreByAI=1.0` 반환 → `finalScore=0.64`로 오버레이 경고 정상 표시. 약한 키워드("지금 받기", weight 0.8)만 있는 경우엔 `finalScore=0.512`로 임계값 미달 → 오버레이 미표시 (의도된 보수적 동작, 오탐 방지).

**남은 주의사항**
- 권한 다이얼로그 기본값이 "Share one app"이므로 사용자가 반드시 "Share entire screen"으로 바꿔야 함. 다른 앱의 광고를 감지하는 게 목적이므로 "Share one app"을 고르면 기능이 사실상 무력화됨 — **온보딩 단계에서 이 선택을 안내하는 UI/설명이 필요** (현재는 시스템 기본 다이얼로그만 띄우고 별도 안내 없음).
- `POST_NOTIFICATIONS` 런타임 권한 요청은 아직 구현 안 함 (Manifest 선언만 있음). Android 13+ 에서 이 권한이 없으면 포그라운드 서비스 자체는 계속 동작하지만 화면에 알림이 안 보일 수 있음.
- MediaProjection 권한 토큰은 프로세스 생명주기에 종속적이라 앱이 완전히 종료되면 재요청이 필요함 (매 앱 시작마다 다이얼로그가 뜨는 건 의도된 동작).

## 블랙리스트 원격 소스 연동 완료 (2026-08-10)
`BlacklistUpdateWorker`의 플레이스홀더 URL(`example.com`)을 실제 공개 블랙리스트 2종으로 교체하고 에뮬레이터에서 실동작 검증함.

**소스 (`BlacklistUpdateWorker.BLACKLIST_SOURCES`)**
- 기본: `https://raw.githubusercontent.com/smed79/blacklist/master/hosts.txt` — hosts 형식 (`0.0.0.0 domain.com`)
- 보조: `https://github.com/List-KR/List-KR/raw/master/filter.txt` — AdGuard 필터 형식 (`||domain.com^`)

**구조 (`BlacklistRepository.kt`)**
- `BlacklistSource(url, format)` + `BlacklistFormat { HOSTS, ADGUARD, PLAIN }`로 소스별 파서를 지정
- `refreshFromRemote(sources: List<BlacklistSource>)`가 순차 다운로드 → 포맷별 파싱 → `Set`으로 합쳐 중복 제거 → DB에 `replaceAll`. 소스 하나가 실패해도 나머지는 계속 시도하고, 하나라도 성공하면 그 결과를 반영 (전부 실패 시에만 기존 DB 유지)
- `parseHostsFormat()` — `#` 이후 주석 제거, `0.0.0.0`/`127.0.0.1` 다음 토큰을 도메인으로 추출
- `parseAdGuardFormat()` — `||domain.com^` 패턴만 추출, `!` 주석/`[...]` 헤더/`@@` 예외 규칙은 제외
- 기존 단일 URL 시그니처(`refreshFromRemote(url: String)`)는 PLAIN 포맷으로 위임하는 오버로드로 유지 (`AdDetector.updateBlacklist()` 호환용)

**에뮬레이터 실동작 검증 결과**: 두 소스 합쳐 **144,135개 도메인**, 중복 제거 후 저장 성공. 파싱 오염 검사(공백/`|`/`^`/IP 접두사 잔존 여부) 결과 **malformedCount=0**. `doubleclick.net`, `googlesyndication.com`, `adnxs.com`, `outbrain.com` 등 알려진 광고 도메인 전부 포함 확인.

## 유튜브/인스타그램 광고 레이블 배너 추가 완료 (2026-08-10)
정상적인 광고가 섞여 나오는 유튜브/인스타그램에서는 기존 차단 팝업(`showWarning`) 대신, 영상 재생은 방해하지 않는 상단 정보 배너를 띄우도록 별도 경로를 추가함.

**구조**
- `AdDetector.kt` — `adLabelPackages: Map<String, Set<String>>`에 대상 패키지·레이블 정의:
  - `com.google.android.youtube`: "광고", "Ad", "광고 건너뛰기", "건너뛰기", "5초 후 건너뛸 수 있습니다"
  - `com.instagram.android`: "광고", "Sponsored"
  - `isAdLabelPackage(packageName)` / `matchesAdLabel(packageName, texts)` — 가중치 없는 단순 boolean 매칭 (일반 `scoreByKeywords`와 별개, 정보 배너 전용)
- `AdGuardAccessibilityService.kt` — `TYPE_WINDOW_CONTENT_CHANGED`에서 `detector.isAdLabelPackage(packageName)`이 true면 기존 `analyzeWindowContent`(차단 팝업 파이프라인)를 완전히 건너뛰고 `checkAdLabel()`만 실행 → 매칭 시 `overlayManager.showAdInfoBanner()` 호출
- `OverlayManager.kt` — `showAdInfoBanner()` 신규: "📢 광고가 재생 중입니다" 텍스트, 상단 고정(`Gravity.TOP`), 반투명 검정 배경, `FLAG_NOT_TOUCHABLE`로 터치 통과(영상 조작 방해 없음), 3초 후 `Handler.postDelayed`로 자동 dismiss. `showWarning()`과는 완전히 별개의 오버레이 창(별도 `bannerView`/`bannerDismissRunnable` 필드)이라 동시에 떠도 서로 간섭하지 않음

**에뮬레이터 실동작 검증**: 실제 유튜브 광고를 그때그때 재현할 수 없어(에뮬레이터에서 Instagram 미설치, YouTube 광고는 임의 재현 불가), 우리 앱 자체를 임시로 감지 대상 패키지에 포함시켜 파이프라인을 검증함 — 텍스트 매칭(`matchesAdLabel`) 정상 동작, 배너가 화면 상단에 정확한 문구·스타일로 표시됨을 스크린샷으로 확인, Android 시스템 로그(`WindowManager: ... FLAG_NOT_TOUCHABLE ... setting alpha to 0.80 to let touches pass through`)로 터치 패스스루가 실제로 적용됨을 확인, 일정 시간 후 자동 dismiss까지 확인. 테스트용 임시 코드는 모두 제거하고 원복.

## 다음 할 일
1. 카카오 개발자 콘솔 → 앱 설정 → 비즈니스 → **비즈 앱 전환** (개인 비즈 앱 가능, 사업자등록번호 불필요)
2. **카카오톡 채널 연결** (비즈 앱 전환 시 필요할 수 있음)
3. 제품 설정에서 **친구 API/피커**, **메시지 API** 각각 사용 신청 → 카카오 검수 승인 대기
4. 검수 승인 전에는 앱 소유자/팀 멤버 계정으로만 테스트되므로, 비즈 앱 전환 직후 동일 계정(`skdlatjsdk@gmail.com`)으로 재시도해 친구 피커 정상 동작 확인
5. ~~자체 블랙리스트 서버 URL 확보 및 교체~~ — 완료 (위 "블랙리스트 원격 소스 연동 완료" 참고, 공개 소스 사용으로 자체 서버는 보류)
6. Room DB 블랙리스트 단위 테스트 작성 (Repository/Dao)
7. ~~MediaProjection 연동 구현~~ — 완료 (위 "MediaProjection 연동 완료" 참고)
8. MediaProjection 권한 다이얼로그에서 "Share entire screen"을 선택하도록 안내하는 온보딩 UI 추가 (기본값 "Share one app"으로는 기능이 무력화됨)
9. `POST_NOTIFICATIONS` 런타임 권한 요청 플로우 추가 (Android 13+ 대응)

---

## 주의사항
- `SYSTEM_ALERT_WINDOW` 권한: Play Store 심사 시 접근성 목적 명시 필요
- 일부 제조사(Samsung 등) 배터리 최적화로 AccessibilityService 강제 종료 가능
- 오탐(false positive) 방지: 임계값 0.6으로 보수적 설정

# SeniorAdGuard

어르신 스마트폰의 광고를 자동으로 감지하고 닫아주는 안드로이드 앱입니다.

---

## 어떻게 동작하나요?

화면에 광고가 나타나면 3단계로 처리합니다.

| 단계 | 방법 | 결과 |
|------|------|------|
| 1단계 | 광고 키워드/패턴 즉시 감지 | 자동으로 닫힘 |
| 2단계 | AI(Gemini)가 애매한 광고 판별 | "AI 광고 같아요" 표시 후 닫힘 |
| 3단계 | 의심스러운 앱 설치 차단 | 설치 전 경고 |

두 가지 모드가 있습니다.
- **어르신 모드**: 광고를 조용히 자동 차단
- **보호자 모드**: 어르신 기기에서 감지된 광고 알림 수신 + 대시보드 확인

---

## 시작하기 전에 필요한 것

### 1. Gemini API 키 발급
1. [Google AI Studio](https://aistudio.google.com) 접속
2. API 키 생성
3. `local.properties`에 추가:
   ```
   GEMINI_API_KEY=여기에_키_입력
   ```

### 2. Firebase 연결
1. [Firebase Console](https://console.firebase.google.com)에서 프로젝트 생성
2. Android 앱 등록 (패키지명: `com.example.senioradguard`)
3. `google-services.json` 다운로드 → `app/` 폴더에 넣기
4. Realtime Database 생성 (테스트 모드로 시작)
5. Cloud Messaging 활성화

> Firebase 무료 플랜(Spark)으로 충분합니다.

---

## 현재 되는 것 ✅

- 광고 키워드 자동 감지 및 닫기
- Gemini AI로 애매한 광고 판별 (gemini-2.5-flash-lite)
- "AI 광고 같아요" 노란 테두리 표시
- 앱 설치 차단 경고
- 보호자 앱에 실시간 알림 전송 (FCM)
- 보호자 대시보드에서 차단 내역 확인
- 기기 2대로 크로스 네트워크 실시간 동기화 확인 완료

## 현재 안 되는 것 ❌

- URL 위험도 분석 (코드는 있지만 연결 안 됨)
- 블랙리스트 자동 업데이트 (배터리 이슈로 비활성화)
- 앱 삭제 기능
- 화이트리스트 관리

---

## 앱 설치 후 필수 설정

앱을 설치하거나 재설치하면 **반드시** 아래 두 가지를 켜야 합니다.

1. **접근성 서비스 활성화**
   설정 → 접근성 → SeniorAdGuard → 켜기

2. **알림 권한 허용**
   (처음 실행 시 팝업에서 허용)

> 재설치하면 접근성 서비스가 자동으로 꺼집니다. 반드시 다시 켜세요.

---

## 절대 건드리면 안 되는 것 ⚠️

- `AdLabelRules.kt` — 광고 판별 규칙 파일. 잘못 수정하면 광고 감지가 통째로 망가집니다.

---

## 테스트할 때 참고

- Gemini 키가 없으면 AI 판별 대신 "광고 아님"으로 처리됩니다 (StubClassifier)
- Firebase 없으면 보호자 알림 기능만 동작 안 하고 나머지는 정상 동작
- 같은 광고는 30일간 캐싱 (중복 API 호출 없음)

---

## 프로젝트 구조 (간단히)

```
app/src/main/java/com/example/senioradguard/
├── service/          ← 핵심: 화면 감지 + 광고 처리
├── classifier/       ← Gemini AI 연동
├── pipeline/         ← 광고 판별 파이프라인
├── firebase/         ← 보호자 알림 전송
└── ui/               ← 설정 화면
```

---

테스트 관련 상세 내용이나 내부 구조가 궁금하면 `TECHNICAL.md`를 참고하세요.


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
├── remote/FirebaseRepo.kt      익명 인증 · 역할 · 연결 · 이벤트 기록/구독
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


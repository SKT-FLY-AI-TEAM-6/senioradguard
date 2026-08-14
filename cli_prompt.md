# SeniorAdGuard — Phase 2 구현 명령

## 현재 상태

Android AccessibilityService 기반 광고 차단 앱. 아래가 현재 동작하는 것들이다.

- **Layer 1**: 키워드/패턴 기반 즉시 감지 (AdLabelRules.kt)
- **Layer 2**: Gemini API 판별 (gemini-2.5-flash-lite, GeminiClassifier.kt)
- **Layer 3**: 앱 설치 차단
- **Room DB**: 광고 판정 캐시 (TTL 30일, SHA-256 키)
- **Firebase Realtime DB + FCM**: 익명 인증(anonymous auth), 보호자 알림
- **두 가지 모드**: 어르신(senior) / 보호자(guardian) — SetupActivity에서 선택

아래는 코드는 있지만 현재 비활성화된 것들이다.

- `BlacklistUpdateWorker.schedule()` → 주석 처리됨 (배터리 이슈)
- `DomainMatcher.kt` → 인스턴스화 안 됨 (dead code)
- `AdDetector.kt` → 인스턴스화 안 됨 (dead code)

---

## 목표 아키텍처

### 어르신 모드 내부 흐름

```
AccessibilityNode 수집
  ├─ text 있음 → Layer 1 키워드 판별
  └─ text 없음(ImageView/WebView) → ML Kit OCR → Layer 1 키워드 판별
        ↓
  URL 추출
        ↓
  Room DB 캐시 확인
  ├─ 히트 → 캐시된 위험도 즉시 적용
  └─ 미스 → URL 분석 모듈(DomainMatcher) → Gemini API 통합 판단
        ↓
  저위험 표시 / 중위험 사용자 확인 요청 / 고위험 차단
        ↓
  고위험 → Firebase 저장 + FCM 발송
  중위험 → Firebase 카운트만 기록
```

### 보호자 모드 연동 흐름

```
고위험 차단 이벤트 Firebase 저장
  (저장 데이터: 위험등급, 유형, 차단여부, 발생시각, 앱 패키지명만)
  (전송 금지: 화면 이미지, 페이지 내용)
        ↓
FCM → 보호자 기기 알림
        ↓
보호자가 대시보드에서 확인
        ↓
보호 수준 또는 알림 설정 변경
        ↓
Firestore 리스너로 어르신 기기에 즉시 동기화
```

### 가족 계정 구조 (Firebase)

```
families/{familyId}/
  members/{userId}    → role: "senior"|"guardian", fcmToken, deviceName
  settings/{userId}   → protectionLevel: 1|2|3, whitelist[]
  events/{eventId}    → timestamp, riskLevel, type, blocked, packageName
  reports/{YYYY-MM}   → adsBlocked, urlsBlocked, appsBlocked
```

---

## 구현 태스크 (우선순위 순)

### Phase 2-A: 인증 + 가족 계정

1. **Firebase Auth 전환**
   - anonymous auth → Firebase Auth (Google Sign-In)
   - Realtime DB → Firestore 마이그레이션
   - 기존 FCM 토큰 등록 로직을 새 스키마에 맞게 수정

2. **가족 연결 기능**
   - 보호자: familyId 생성 + 6자리 초대 코드 발급
   - 어르신: 코드 입력 → families/{familyId}/members에 등록
   - SetupActivity에서 모드 선택 시 연동

### Phase 2-B: URL 분석 모듈 활성화

3. **DomainMatcher 연결**
   - `AdGuardAccessibilityService.kt` Layer 3 클릭 감지 지점에서 URL 추출
   - `DomainMatcher.isDangerous(url)` 호출
   - 위험 도메인이면 오버레이 표시 + 이벤트 기록

4. **BlacklistUpdateWorker 재활성화**
   - `schedule()` 주석 해제
   - 다운로드 주기를 주 1회 → 월 1회로 변경 (배터리 절약)
   - 백그라운드 다운로드 완료 후 Room DB 갱신

### Phase 2-C: OCR 추가

5. **ML Kit Text Recognition 연동**
   - `build.gradle`에 `com.google.mlkit:text-recognition-korean` 추가
   - 트리거 조건: `node.text == null && (node is ImageView || node is WebView)`
   - OCR 결과를 기존 Layer 1 파이프라인으로 전달

### Phase 2-D: 3단계 위험도 체계

6. **위험 등급 분류**
   - Layer 1 키워드 히트 → 저위험 (자동 처리)
   - Gemini 판별 isAd=true, confidence < 0.8 → 중위험 (사용자 확인 요청)
   - Gemini 판별 isAd=true, confidence ≥ 0.8 또는 DomainMatcher 히트 → 고위험 (즉시 차단)

7. **보호 수준 설정 (1/2/3단계)**
   - protectionLevel 1: Layer 1만
   - protectionLevel 2: Layer 1 + 2
   - protectionLevel 3: Layer 1 + 2 + URL 차단
   - 보호자 앱에서 설정 변경 → Firestore → 어르신 기기 실시간 반영

### Phase 2-E: 보호자 대시보드

8. **차단 내역 리스트**
   - Firestore `events` 컬렉션 쿼리 (familyId 기준)
   - RecyclerView로 표시

9. **안전 리포트**
   - `reports/{YYYY-MM}` 집계 (월간)
   - 주간 FCM 요약 알림

10. **앱 설치 감지 + 삭제 권고**
    - `BroadcastReceiver`: `ACTION_PACKAGE_ADDED` 수신
    - Google Play 외 출처이면 → Firebase 이벤트 기록 + 보호자 FCM
    - 보호자가 삭제 권고 → 어르신에게 알림

---

## 제약 조건 / 주의사항

- `AdLabelRules.kt`는 절대 수정하지 마라. 광고 판별 규칙 파일이다.
- Gemini responseSchema는 `{isAd: Boolean, confidence: Float, reason: String}` 형태를 유지해라.
- Firebase에 저장할 수 있는 데이터: 위험등급, 유형, 차단여부, 발생시각, 앱 패키지명. 화면 이미지나 페이지 내용은 절대 전송하지 마라.
- Room DB 캐시 키는 SHA-256 (숫자 정규화 포함), TTL 30일 유지.
- Gemini API 키는 `local.properties`의 `GEMINI_API_KEY`에서만 읽어라.
- `google-services.json`은 `app/` 폴더에 있다.
- GeminiClassifier가 실패하면 StubClassifier로 fallback하는 기존 로직을 유지해라.

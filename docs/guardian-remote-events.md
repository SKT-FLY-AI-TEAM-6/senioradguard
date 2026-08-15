# 보호자 원격 기록(이벤트) 전체 정리

> 기준: `merge/guardian-all` (realGuArDian2 `main`, 2026-08-15)
> 보호자에게 가는 데이터는 **아래 7종의 이벤트가 전부**다. 이 문서에 없는 것은 기기 밖으로 나가지 않는다.

---

## 1. 전달 경로 — 어디로, 어떻게 가는가

```
어르신 폰                                                 보호자 폰
─────────────────────────────────────────────            ─────────────────────────
감지 코드 (아래 7종의 발생 지점)
   ↓
AdEventLogger / GuardianEventLogger                       GuardianActivity
   ↓  (logger/AdEventLogger.kt, GuardianEventLogger.kt)      ↑ 실시간 구독
FirebaseRepo.logEvent()                                   FirebaseRepo.observeEvents()
   ↓  (remote/FirebaseRepo.kt:219)                           (ValueEventListener)
Firebase Realtime Database
   events/{어르신 userId}/{eventId}
```

- **푸시 알림이 아니다.** 보호자 폰의 `GuardianActivity`가 `events/{연결된 어르신 userId}` 노드에
  `ValueEventListener`를 붙여 두고, 새 이벤트가 쓰이면 목록이 자동 갱신되는 구독 방식이다.
- **익명 인증을 먼저 거친다.** 보안 규칙이 `auth != null`을 요구하기 때문. 앱이 뜨자마자 광고를
  잡으면 인증(보통 1초 이내)보다 빠를 수 있어, 인증 전 발생분은 메모리 큐(최대 20건)에 쌓았다가
  인증 완료 후 순서대로 흘려보낸다 (`FirebaseRepo.pending`).
- **google-services.json이 없으면 전부 no-op.** 원격 기록만 조용히 꺼지고 감지·경고 기능은 그대로 돈다.

### 이벤트 한 건의 스키마 (모든 이벤트 공통)

```
events/{userId}/{eventId}
  timestamp   : Long    — 발생 시각 (epoch millis)
  appPackage  : String  — 출처 (도메인/패키지명, 또는 빈 값)
  adText      : String  — 요약 문구 또는 이벤트 타입 토큰 (원문 아님, 마스킹 후 최대 120자)
  action      : String  — "warned" | "blocked" | "ignored"
  layer       : Int     — 1(라벨 감지) | 2(AI 판별) | 3(개입·차단)
```

---

## 2. 이벤트 7종 — 어디서, 어떻게, 왜

### ① 광고 표시 — `action=warned, layer=1|2`

| 필드 | 값 |
|---|---|
| appPackage | 광고가 뜬 출처 — 도메인(`hankyung.com`) 또는 앱 패키지명 |
| adText | `"광고 N건 표시"` |

- **어디서**: `AdGuardAccessibilityService.reportSighting()` (service/AdGuardAccessibilityService.kt:994)
  → `AdEventLogger.logAdMarked()`. 스캔 결과가 화면에 반영되는 `apply()`에서 Layer 1(확정 테두리)과
  Layer 2(AI 점선) 각각에 대해 호출된다.
- **어떻게**: 화면 스캔이 광고 영역을 찾아 테두리를 그린 순간. Layer 2는 유휴 판별 완료 시에도 한 번 더 경로가 있다.
- **왜**: 보호자가 알아야 할 것은 "어디서 광고가 몇 건 떴는가"이지 광고 내용이 아니다. 그래서 문구
  원문 대신 건수 요약만 보낸다.
- **중복 제어**: `SightingLog`이 **출처+레이어 조합당 1회**만 통과시킨다. 한 페이지를 스크롤하며 같은
  광고가 실측 40회 다시 표시돼도 보호자에게는 1건만 간다. 서비스 생존 동안만 기억(LRU 128).

### ② 스토어 강제 이동 경고 — `action=warned, layer=3`

| 필드 | 값 |
|---|---|
| appPackage | 스토어 패키지 (`com.android.vending` 등) |
| adText | `"앱 설치 화면으로 이동"` |

- **어디서**: `InstallGuard.onStoreRedirect()` (guard/InstallGuard.kt:44) → `AdEventLogger.logStoreRedirect()`.
- **어떻게**: 접근성 이벤트에서 Play스토어/갤럭시스토어 창 전환(`TYPE_WINDOW_STATE_CHANGED`)이 감지되면
  서비스 dispatch ④가 InstallGuard로 넘기고, 경고 팝업("뒤로 돌아갈까요?")을 띄우면서 기록한다.
- **왜**: 광고 클릭이 스토어 설치 화면으로 이어지는 흐름은 어르신이 의도하지 않은 설치의 첫 단계라,
  경고를 띄웠다는 사실 자체를 보호자가 알아야 한다.

### ③ 설치 유도 버튼 경고 — `action=blocked, layer=3`

| 필드 | 값 |
|---|---|
| appPackage | (빈 값) |
| adText | 눌린 버튼 문구 — `CardText.mask()`로 전화·카드·주민번호 제거 후 120자 절단 |

- **어디서**: `InstallGuard.onClick()` (guard/InstallGuard.kt:65) → `AdEventLogger.logInstallBlocked()`.
- **어떻게**: 대상 앱에서 `TYPE_VIEW_CLICKED`가 오면 눌린 문구를 `InstallTriggerRules.isInstallTrigger()`로
  판정("설치하기" 등 위험 문구). 해당하면 50ms 뒤 경고 팝업을 덮으며 기록한다.
- **왜**: 설치가 실행되기 직전의 개입이라 7종 중 유일하게 `blocked`다. 버튼 문구는 무엇을 막았는지
  보호자가 이해하는 데 필요해서 남기되, 마스킹·절단으로 개인정보 유출을 막는다.

### ④ 경고 무시 — `action=ignored, layer=3`

| 필드 | 값 |
|---|---|
| appPackage | 경고가 떠 있던 패키지 |
| adText | `"스토어 이동 경고를 그냥 봄"` 또는 `"'버튼문구' 경고를 그냥 봄"` |

- **어디서**: ②·③ 경고 팝업의 "그냥 보기" 버튼 콜백 (guard/InstallGuard.kt:39, 59) → `AdEventLogger.logIgnored()`.
- **어떻게**: 어르신이 경고를 보고도 계속 진행을 선택한 순간.
- **왜**: 보호자 입장에서 "경고가 떴다"와 "경고를 무시하고 진행했다"는 다른 정보다. 후자가 반복되면
  보호자가 직접 개입할 근거가 된다.

---

아래 3종은 이번 병합(merge/guardian-all)에서 추가된 것으로, `GuardianEventLogger`
(logger/GuardianEventLogger.kt)를 거친다. 공통으로 **appPackage는 빈 값, action=warned, layer=3**이고,
이벤트 타입 토큰이 adText 자리에 실린다. **도메인명·URL·검색어·화면 내용은 싣지 않는다** —
어르신이 무엇을 보고 있었는지는 보호자에게도 넘기지 않는다는 phase4 원칙을 그대로 따른 설계다.

### ⑤ `DOMAIN_BLOCKED` — 악성 도메인 도착 경고

- **어디서**: `AdGuardAccessibilityService.checkBlockedHost()` (service/AdGuardAccessibilityService.kt:1820)
  → `GuardianEventLogger.logDomainBlocked()`.
- **어떻게**: 브라우저(크롬·삼성 인터넷) 주소창의 도메인을 14만 건 블랙리스트(`assets/blacklist.txt`,
  Room 시드)와 대조 → 히트 → **1.5초 대기 후에도 그 주소에 머물러 있으면** (스쳐 가는 광고망 중계 제외)
  전체화면 경고("안전하게 돌아가기" 버튼 하나)를 띄우면서 기록한다. 검색 결과 화면에서는 serp 배지가
  대신하므로 이 경로는 건너뛴다.
- **왜**: 신고로 확정된 사기·피싱 도메인 도착은 가장 확실한 위험 신호다. 다만 보호자에게는 "위험한
  곳에 갔고 앱이 경고했다"는 사실만 필요하므로 도메인명은 보내지 않는다.
- **중복 제어**: `warnedBlockedHosts`(SightingLog)가 같은 도메인 반복 경고·반복 기록을 막는다.

### ⑥ `APK_INSTALL_WARNING` — DBD(스토어 미경유) APK 설치 경고

- **어디서**: `AdGuardAccessibilityService.warnDirectDownloadInstall()` (service/AdGuardAccessibilityService.kt:1849)
  → `GuardianEventLogger.logApkInstallWarning()`.
- **어떻게**: 시스템 패키지 인스톨러의 설치 확인 화면이 떴는데(`InstallSourceGuard.isInstallerScreen`)
  **직전 포그라운드 앱이 스토어가 아니면** — 즉 브라우저·문자 등에서 바로 내려받은 APK(Drive-by-Download)면 —
  전체화면 경고("설치 취소하고 돌아가기" 버튼 하나)를 덮으면서 기록한다. ②·③(스토어 경유 흐름)과는
  트리거가 다른 별개 기능이다.
- **왜**: 스토어를 거치지 않은 APK 설치는 악성 앱 감염의 대표 경로다. 무엇을 설치하려 했는지(APK 이름 등)는
  싣지 않는다 — 사실만으로 보호자 개입 근거가 충분하다.

### ⑦ `SEARCH_RISK_DETECTED` — 검색 결과 최고 위험 감지

- **어디서**: `SerpTracker.runScan()`의 판정 갱신 경로 (com/guradian/serp/SerpTracker.kt) →
  `SerpFeature(onHighRisk=...)` 콜백 → 서비스 (service/AdGuardAccessibilityService.kt:210)
  → `GuardianEventLogger.logSearchRiskDetected()`.
- **어떻게**: 구글 검색 결과 화면(크롬·구글 앱)에서 규칙+Gemini 판별이 결과 칸에 위험 등급을 매기는데,
  그중 **최고 등급(HIGH, 화면 표시 "위험")이 새 호스트에서 나왔을 때만** 승격된다. 중·하위 등급
  (주의/안전/확인 안 됨)은 배지로만 표시되고 원격에는 가지 않는다.
- **왜**: 검색 결과에 위험 표시가 뜨는 것 자체는 흔해서 전부 올리면 소음이 된다. "지금 검색 화면에
  실제 위험(불법 스트리밍·도박·피싱 등)이 노출됐다"는 최고 등급만 보호자가 알 가치가 있다.
- **중복 제어**: `SerpTracker.promotedHosts`가 호스트당 1회만 콜백을 부른다. 호스트 목록은 기기
  메모리에만 있고 전송되지 않는다.

---

## 3. 의도적으로 보내지 않는 것

| 항목 | 이유 / 처리 |
|---|---|
| 광고 문구 원문 | `"광고 N건 표시"`로 요약. Layer 3 버튼 문구만 예외로 남기되 마스킹+120자 절단 |
| 방문 URL·도메인명 (⑤~⑦) | 어르신의 열람 내역을 보호자에게 넘기지 않는다. 사실 토큰만 전송 |
| 검색어 | serp 판별기 입력으로만 쓰이고 이벤트에는 실리지 않음 |
| 전화·카드·주민번호 | `CardText.mask()`가 패턴 삭제 (③의 버튼 문구 포함 모든 adText 경유 지점) |
| URL 위험 판정 결과 (`url_verdict`) | 로컬 Room 캐시 전용 |
| 광고 지문·연계 (`ad_fingerprint_link`) | 로컬 Room 전용 |
| serp 판정 캐시·promotedHosts | 기기 메모리 전용 (LRU/세트) |
| 가림막·복귀 바·배지의 표시 이력 | 원격 기록 없음 — 위 7종 이벤트로만 요약됨 |
| `adb logcat` 진단 로그 (`AdGuard`/`GurADian` 태그) | 개발용, 기기 밖으로 나가지 않음 |

## 4. 참고 — 알아둘 것

- **보호자 화면 표시**: `GuardianActivity`는 이벤트를 받은 그대로 보여주므로, 신규 3종은 현재
  `DOMAIN_BLOCKED` 같은 영문 토큰이 그대로 노출된다. 어르신용 문구로 바꾸려면 보호자 화면 쪽에
  타입→문구 매핑을 추가하면 된다 (전송 스키마는 그대로 두고).
- **이벤트가 안 올 때 점검 순서**: google-services.json 존재 → Firebase 콘솔 익명 인증 활성화
  (`CONFIGURATION_NOT_FOUND` 로그 확인) → Realtime Database 보안 규칙 게시 → 연결 코드 일치.
- 발생 지점의 라인 번호는 `merge/guardian-all` 최신 커밋(2d5cc74) 기준이다.

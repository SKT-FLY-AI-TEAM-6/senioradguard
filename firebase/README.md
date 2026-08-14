# Firebase 설정

## 1. Google 로그인 켜기 (필수)

가족 계정은 사람 단위라 계정이 있어야 기기를 바꿔도 연결이 유지된다.

1. 콘솔 → **Authentication → Sign-in method → Google** 사용 설정
2. 콘솔 → 프로젝트 설정 → 내 앱 → **SHA-1 지문 추가**

   디버그 키의 SHA-1은 이렇게 얻는다:
   ```bash
   ./gradlew signingReport | grep -A1 "Variant: debug" | grep SHA1
   ```
3. **`google-services.json`을 다시 받아** `app/`에 덮어쓴다

3번을 빼먹으면 파일에 웹 클라이언트 ID(`client_type: 3`)가 없어 로그인이 실패한다.
빌드 시 `google-services.json에 웹 클라이언트 ID가 없습니다` 경고가 나오면 이 상태다.

## 2. Firestore 만들기

콘솔 → **빌드 → Firestore Database** 생성. (Realtime Database 아님 — 2-A에서 옮겼다)

## 3. 보안 규칙 올리기

`firestore.rules` 내용을 콘솔 → Firestore → **규칙** 탭에 붙여넣고 게시.

**올리기 전에는 아무나 DB 전체를 읽고 지울 수 있다.**

## 데이터 구조

```
families/{familyId}/
  members/{uid}    role, fcmToken, deviceName, joinedAt
  settings/{uid}   protectionLevel, whitelist[]
  events/{id}      timestamp, riskLevel, type, blocked, packageName, count
  reports/{YYYY-MM}  adsBlocked, urlsBlocked, appsBlocked
invites/{code}     familyId          ← 6자리 코드로 가족을 찾는 역인덱스
```

**화면에 뜬 글자는 올라가지 않는다.** 이벤트에 담기는 것은 위험등급·유형·차단여부·
시각·출처(도메인 또는 패키지명)뿐이다.

## 규칙이 막는 것

| | 규칙 전 | 규칙 후 |
|---|---|---|
| 로그인 없이 DB 덤프 | 가능 | 차단 |
| 남의 가족 기록 읽기 | 가능 | 차단 (구성원만) |
| 남의 역할·FCM 토큰 변조 | 가능 | 차단 (자기 문서만) |
| 기록 삭제·조작 | 가능 | 차단 (생성만 허용) |

## 남아 있는 구멍

**초대 코드를 아는 사람은 가족에 들어올 수 있다.** 6자리라 무작위로 맞히기는 어렵지만
(100만분의 1), 코드가 새어나가면 막을 방법이 없다. 코드를 한 번 쓰면 무효화하거나,
어르신 쪽에서 수락하게 하는 절차가 다음 단계다.

`invites/{code}`는 로그인한 사람이면 누구나 **덮어쓸 수 있다.** 남의 코드를 자기
가족으로 바꿔치기할 수 있다는 뜻이다. 코드 발급을 서버(Cloud Functions)로 옮기면
막힌다.

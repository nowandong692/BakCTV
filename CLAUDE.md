# 박CTV 작업 규칙

가족용 실시간 촬영·모니터 앱. 사용자와는 한국어로 대화한다.

## 구조
- `web/` — 웹앱 (보기·웹 촬영). main 에 푸시하면 GitHub Pages 로 자동 배포
- `app/` — 촬영 폰용 안드로이드 앱 (갤럭시 S8, 안드로이드 9 기준). main 에 푸시하면 APK 빌드 → Releases `v1.<빌드번호>`
- `README.md` — 현재 기능·사용법, `CHANGELOG.md` — 변경 이력, `DECISIONS.md` — 주요 결정 사항과 이유

## 변경할 때마다
1. **`CHANGELOG.md` 맨 위에 기록** (날짜, 앱 버전 또는 "웹"). 사용자 눈높이의 한국어로, 무엇이 바뀌었고 왜인지
   - 앱 버전 번호는 푸시 후 빌드 번호로 확인해서 적는다
2. 기능·사용법이 바뀌면 `README.md` 도 고친다
3. `web/` 을 고치면 `web/sw.js` 의 `CACHE` 버전(`family-cam-vN`)을 올린다
4. 사용자와 새로 정한 방향·제외한 기능이 있으면 `DECISIONS.md` 에 이유와 함께 기록
   - 작업 전에 `DECISIONS.md` 를 읽고, 이미 하지 않기로 한 것(예: ntfy)은 다시 제안하지 않는다
5. 커밋 메시지는 한국어로

## 꼭 지킬 것
- 방 코드 규칙·연결 ID 계산은 웹(`web/index.html` 의 `codeProblem`, `roomId`)과 앱(`RoomCode.kt`)이 **항상 같아야** 서로 연결된다
- 앱 ↔ 웹 데이터 메시지(`t: hello / motion / rec / viewers`)와 PeerJS 메시지 형식도 양쪽을 함께 바꾼다
- APK 서명 키는 GitHub Secrets 에만 있다. 저장소에 키를 올리지 않는다

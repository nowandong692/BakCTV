# 박CTV 안드로이드 앱 (촬영 기기용)

화면이 꺼져도 촬영이 계속되는 박CTV 촬영 전용 앱입니다.
웹 버전 박CTV 화면(`https://nowandong692.github.io/BakCTV/`)을 그대로 띄우고,
"박CTV 촬영 중" 알림을 띄운 백그라운드 서비스로 카메라·마이크·와이파이를 유지합니다.

- `web/` 폴더: 웹 버전 박CTV. main 에 올리면 GitHub Pages 로 자동 배포됩니다.
- 보는 쪽 기기는 지금처럼 웹앱(박CTV)을 그대로 쓰면 됩니다.
- `web/` 을 고쳐 올리면 앱도 자동으로 최신 화면이 됩니다. (앱 재설치 불필요)

## 0. GitHub 로 자동 빌드·배포 (추천)

코드를 GitHub 에 올리면 GitHub Actions 가 APK 를 만들어 **Releases** 에 올려 줍니다.

1. GitHub 에서 새 저장소를 **Public** 으로 만듭니다 (예: `bakctv-android`).
   - Private 이면 가족이 APK 를 받으려면 GitHub 로그인이 필요합니다.
   - 방 코드나 서명 키는 코드에 들어 있지 않으니 공개해도 됩니다.
2. 이 폴더의 파일을 모두 저장소에 올립니다 (`.github` 폴더 포함!).
   - 웹에서 올릴 때: 저장소 → **Add file → Upload files** 에 폴더 내용을 끌어다 놓기.
3. 저장소 **Settings → Secrets and variables → Actions → New repository secret** 에서
   `BakCTV-github-secrets.txt` 에 적힌 `KEYSTORE_PASSWORD`, `KEYSTORE_BASE64` 두 개를 추가합니다.
4. **Actions** 탭 → "박CTV APK 빌드" → **Run workflow** (또는 main 에 다시 올리면 자동 실행).
5. 5분쯤 뒤 **Releases** 에 `BakCTV.apk` 가 올라옵니다.

**가족에게 보낼 고정 다운로드 링크** (항상 최신 버전):
`https://github.com/<내아이디>/<저장소이름>/releases/latest/download/BakCTV.apk`

코드를 고쳐 다시 올리면 버전이 자동으로 올라가고, 같은 링크로 받아 설치하면 **덮어쓰기 업데이트**됩니다.

## 1. (대안) Android Studio 로 직접 APK 만들기

1. **Android Studio** 설치: https://developer.android.com/studio (무료)
2. Android Studio 실행 → **Open** → 이 `BakCTV-android` 폴더 선택
3. 아래쪽에서 Gradle 동기화(Sync)가 끝날 때까지 기다립니다. 처음엔 몇 분 걸립니다.
   - 버전 업그레이드를 권하는 알림이 뜨면 무시하거나 그대로 진행해도 됩니다.
4. 메뉴 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
5. 완료 알림의 **locate** 를 누르면 `app/build/outputs/apk/debug/app-debug.apk` 가 나옵니다.

## 2. 촬영 폰에 설치

1. `app-debug.apk` 를 촬영 폰으로 보냅니다 (카톡 나에게 보내기, USB, 구글 드라이브 등).
2. 폰에서 파일을 눌러 설치 → "출처를 알 수 없는 앱" 허용을 묻으면 허용합니다.
3. 처음 실행하면 다음을 모두 **허용**해 주세요.
   - 카메라, 마이크, 알림
   - "배터리 사용량 최적화 중지" (절전 때문에 연결이 끊기는 것을 막습니다)

## 3. 사용법

- 앱 실행 → 방 코드 입력 → **이 기기로 촬영하기**
- 그다음 **전원 버튼으로 화면을 꺼도** 촬영이 계속됩니다.
- 알림줄에 "박CTV 촬영 중"이 떠 있는 동안 촬영 중입니다.
- 끝낼 때: 알림의 **종료** 버튼, 또는 최근 앱 목록에서 박CTV를 밀어서 닫기.
- 뒤로 가기 버튼은 앱을 닫지 않고 뒤로 보내기만 합니다 (촬영 유지).

## 문제 해결

- **화면을 끄면 멈춤**: 설정 → 애플리케이션 → 박CTV → 배터리 → "제한 없음" 으로 변경.
  삼성 폰은 설정 → 배터리 → 백그라운드 사용 제한 → "절전 예외 앱"에 박CTV 추가.
- **웹 주소가 바뀐 경우**: `app/src/main/res/values/strings.xml` 의 `app_url` 을 고친 뒤 다시 빌드.
- 안드로이드 8.0 이상에서 동작합니다.

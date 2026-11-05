> ⚠️ **먼저 읽어 주세요**
>
> 이 프로젝트는 **개인용으로, 취미 삼아** 만들고 있습니다. 정상 동작을 **보장하지 않고**, 버그·데이터 손실·계정 문제가 생겨도 책임지지 않으며, 예고 없이 바뀌거나 망가질 수 있습니다.
> 기능 요청이나 수정에 대한 **기대는 하지 마세요.** 솔직히 **쓰지 않는 것을 추천합니다.** 그래도 쓰신다면 모두 본인 책임입니다.

# MinisX

**내 폰 안에서 돌아가는 개인용 AI 에이전트 (Android 전용).**

[OpenMinis](https://github.com/OpenMinis/OpenMinis)(GPL-3.0)를 바탕으로 Android만 남겨 확장한 포크입니다. 원 프로젝트와 같은 GPL-3.0으로 배포되며, 저작권 표기와 라이선스는 `LICENSE`와 커밋 기록에 그대로 남아 있습니다. Claude, GPT, Gemini 같은
모델에게 기기 안의 리눅스 셸, 브라우저 자동화, 스킬, 영구 메모리, 그리고 기기 기능(건강, 블루투스, NFC,
카메라 이미지 분석 등)을 도구로 쥐여 주는 앱입니다.

> iOS 앱과 iOS 전용 네이티브 의존성(iSH, FFmpeg, LAME)은 제거했습니다. iOS 코드가 마지막으로 들어 있는
> 커밋은 `1a71df0`이고, `git show 1a71df0:src/ios/...` 로 볼 수 있습니다.

---

## 주요 기능

| | |
|---|---|
| **원하는 모델 사용** | Claude, GPT, Gemini 등을 내 API 키 또는 계정 로그인으로 사용합니다. |
| **진짜 리눅스 셸** | 기기 안의 샌드박스 Alpine Linux(PRoot)에서 패키지 설치, 스크립트 실행, 파일 작업을 합니다. |
| **기기 연동 도구** | 에이전트가 셸에서 `android-*` 명령으로 기기 기능을 씁니다. (아래 표) |
| **브라우저 자동화** | 에이전트가 웹을 직접 탐색하고 조작합니다. |
| **스킬 · 메모리** | `SKILL.md` 기반 스킬과 세션을 넘나드는 메모리를 지원합니다. Claude/Codex용 스킬도 대부분 그대로 동작합니다. |
| **작업 공간** | 작업을 컨텍스트별로 나누고 `minis://workspace/` 로 접근합니다. |
| **백업** | rclone 기반으로 SMB / WebDAV / SFTP / S3 / FTP 에 백업하고 복원합니다. |
| **네트워크** | 프록시와 Tailscale 설정을 앱 전체 통신에 적용합니다. |
| **개인정보 보호** | 생체 인증으로 앱과 개별 대화를 잠그고, 긴 작업 중에는 화면 보호기를 씁니다. |
| **음성 대화** | 입력창의 Live 버튼으로 모델과 실시간으로 말하며, 일을 시키면 에이전트가 처리합니다. |

---

## 에이전트 기기 도구 (`android-*`)

셸의 `/usr/local/bin/android-*` 명령입니다. 출력은 JSON이고 `--compact`, `-q` 옵션을 지원하며, 설정 →
권한에서 도구별로 허용 수준을 정합니다. 옵션은 `<도구> --help` 로 확인합니다.

| 도구 | 기능 |
|---|---|
| `android-alarm` · `android-calendar` · `android-contacts` | 알람/타이머, 캘린더 일정, 연락처 |
| `android-clipboard` · `android-notification` · `android-open` | 클립보드, 알림 보내기/읽기, URL·인텐트 열기 |
| `android-device` · `android-location` · `android-weather` | 기기·배터리·저장공간, 위치와 지오코딩, 날씨 예보 |
| `android-photos` · `android-player` · `android-speak` · `android-speech` | 사진 라이브러리, 오디오 재생, TTS, 음성 인식 |
| `android-health` | **Health Connect.** 걸음 수, 심박, 수면, 운동, 체중, SpO₂, 혈당, HRV(RMSSD), 영양, 기초대사, 케이던스, 고도, 일일 요약을 읽고, 이 앱이 쓴 기록은 `log` / `delete` 합니다. |
| `android-bluetooth` | **BLE.** 스캔, 연결, 서비스 조회, 읽기/쓰기, 알림 구독. 연결은 명령 사이에 유지되고 5분 유휴 시 끊습니다. |
| `android-nfc` | **NFC.** NDEF 읽기/쓰기, 원시 태그 읽기/쓰기, ISO 7816 APDU, FeliCa. 앱이 화면에 떠 있어야 합니다. |
| `android-vision` | **온디바이스 이미지 분석.** OCR(라틴·중국어·일본어·한국어), 바코드, 라벨, 객체, 얼굴, 유사도, 스크롤 캡처 겹침 계산 |
| `android-nlp` | **온디바이스 텍스트.** 언어 감지, 토큰 분리, 개체 추출(날짜·주소·이메일·전화·URL·금액·항공편·운송장 번호) |
| `android-maps` | OpenStreetMap / OSRM 기반 장소 검색, 경로, 소요 시간 (자동차·도보·자전거) |
| `android-media` | 재생 중인 미디어 세션 제어, 볼륨, 기본 음악 앱으로 검색 전달 |
| `android-a11y-cli` · `android-shizuku-cli` | 접근성 서비스로 화면 조작, Shizuku로 시스템 권한 호출 (기본은 꺼짐) |

참고할 점은 다음과 같습니다.

- Health Connect는 Android 14 이상에 내장돼 있고, 그 미만에서는 Health Connect 앱을 설치해야 합니다.
- `android-maps` 는 질의와 좌표를 OpenStreetMap 서비스로 보냅니다. OCR·비전 모델은 앱에 들어 있어 오프라인으로
  동작하고, `android-nlp ner` 만 처음 한 번 작은 언어 모델을 내려받습니다.
- iOS에는 있지만 Android에서는 대응이 없어 "지원 안 함"으로 답하는 기능: 감성 분석, 품사 태깅, 임베딩
  (`android-nlp`), 대중교통 경로(`android-maps`), HomeKit, Apple 미리 알림, Health Connect에 없는 기록
  (ECG, 청력 검사, 시력 처방, 정신건강 설문, 감정 기록).
- NFC `read-emv`(결제 카드 정보 읽기)는 옮기지 않았습니다. 범용 `apdu` 명령으로 같은 일을 할 수 있습니다.

---

## 보안과 일상 사용

- **생체 인증 잠금** (설정 → 생체 인증 잠금): 앱 전체를 잠그거나, 세션 목록에서 대화를 길게 눌러 개별 대화를
  잠글 수 있습니다. 잠긴 대화는 풀기 전까지 제목과 미리보기가 가려지고, 앱 잠금이 켜져 있으면 최근 앱 화면에도
  내용이 보이지 않습니다. 다시 잠그는 시점은 30초 ~ 1시간, 안 함, 나갈 때 중에서 고릅니다.
- **화면 보호기** (설정 → 화면): "화면 켜짐 유지"가 켜져 있고 작업이 도는 동안, 30초 ~ 5분 터치가 없으면 화면이
  검게 바뀌고 현재 단계만 표시됩니다. 터치하면 돌아옵니다.
- **홈 화면 위젯 · 바로가기**: 새 대화 / 음성 / 카메라 버튼과 최근 대화를 보여 주는 위젯이 있고, 최근 대화 3개는
  런처 바로가기로도 나옵니다. (잠긴 대화는 제외)
- **사용량 위젯**: Codex / Claude / Kimi Code 구독 한도를 서비스별 위젯으로 볼 수 있습니다. 가장 빡빡한 한도를
  큰 숫자와 칸 게이지로 보여 주고(20% 이하이면 빨간색), 다음 한도, 초기화까지 남은 시간, 마지막 갱신을 함께
  표시합니다. 위젯을 누르면 해당 프로바이더 설정이, ↻ 를 누르면 그 서비스만 새로고침됩니다. 숫자는 앱의 한도
  화면과 같은 데이터를 쓰므로 위젯이 따로 네트워크 호출을 늘리지 않습니다.
- **실시간 음성 대화(Live)**: 입력창 마이크 옆 파형 버튼으로 모델과 말로 대화합니다. 말을 끊고 끼어들 수 있고,
  "이 폴더 파일 정리해 줘"처럼 일을 시키면 해당 대화의 에이전트가 평소처럼 도구를 써서 처리한 뒤 결과를 음성으로
  읽어 줍니다(요청과 답은 채팅에도 남음). 앱을 나가거나 화면을 꺼도 통화 알림(종료 버튼)과 함께 유지됩니다.
  OpenAI Realtime API를 쓰므로 **API 키가 있는 OpenAI 프로바이더**가 필요합니다(ChatGPT 로그인 불가). 버튼을
  길게 누르면 다음 통화의 목소리를 고릅니다.
- **`minis://action/ask?prompt=…`**: 새 대화를 열고 입력창에 문장만 채웁니다. 링크는 어떤 앱이나 웹페이지도 열 수
  있으므로 자동으로 전송하지 않고, 직접 확인한 뒤 보내기를 누르게 했습니다.

---

## 활용 예시

- **식사 사진 → 영양 기록**: 사진 속 음식을 인식해 칼로리와 영양소를 추정하고 Health Connect에 기록합니다.
- **단톡방 → 할 일**: 텔레그램 대화에서 버그와 할 일을 뽑아 중복을 정리하고 캘린더에 넣습니다.
- **옵시디언 볼트**: 볼트를 작업 공간으로 마운트해 조사하고 정리한 마크다운을 다시 써 넣습니다.
- **사진 속 문서 읽기**: `android-vision ocr` 로 기기 안에서 글자를 뽑고 모델이 요약합니다.
- **공유 → 일정**: 공유 시트로 페이지나 메시지를 보내면 시간과 장소까지 넣은 일정을 만듭니다.

---

## 스킬

**스킬**은 `SKILL.md` 가 있는 폴더입니다. 지침과 선택적으로 스크립트, 참고 자료, 에셋을 담고, 요청이 맞을 때만
에이전트가 불러옵니다. 메타데이터만 컨텍스트에 상주하고 본문은 쓸 때 로드됩니다. Claude, Codex, OpenClaw,
Hermes Agent용 스킬은 대체로 그대로 동작하고, Minis 도구에 맞춘 스킬은 리눅스 셸과 기기 도구를 직접 쓸 수 있어
더 잘 동작합니다.

---

## 소스에서 빌드하기

앱 안에 리눅스 샌드박스가 들어 있어서, 네이티브 의존성(PRoot, rclone)과 Alpine 루트 파일시스템은 바이너리를
커밋하지 않고 **소스에서 빌드**합니다. 전체 절차와 문제 해결은 **[BUILDING.md](BUILDING.md)** 에 있습니다.

```sh
git clone --recurse-submodules https://github.com/sampple-korea/MinisX.git
cd MinisX

# NDK r28+, Go 1.25+, gomobile 필요
./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
./deps/build_rclone_android.sh
mkdir -p src/android/app/libs && cp deps/build/rclone/rclone.aar src/android/app/libs/

cd src/android
./gradlew :app:assembleGithubDebug     # → app/build/outputs/apk/github/debug/
```

- 위 과정을 한 번에: `./scripts/build_android.sh` (릴리스 APK) 또는 `./scripts/build_android.sh debug`. 없는 네이티브
  산출물만 빌드하고, 끝에 서명 인증서 지문을 출력합니다. proot 빌드에는 `gawk`가 필요합니다.
- **서명**: 릴리스 키는 저장소에 없고 GitHub Actions 시크릿(`MINISX_KEYSTORE_B64`, `MINISX_KEYSTORE_PASSWORD`)에만
  있습니다. CI가 그 키로 서명하고, 인증서 지문이 `BUILDING.md`의 값과 다르면 빌드를 실패시킵니다. 로컬 빌드는 본인의
  디버그 키로 서명되므로 릴리스 APK와는 서로 덮어 설치되지 않습니다. 릴리스 키를 바꾸거나 잃어버리면 업데이트가 안 되고
  지웠다 다시 깔아야 합니다(데이터 삭제). 자세한 내용은 `BUILDING.md`의 Signing.
- **CI**: Actions에서 "Android"를 수동 실행하거나 `v0.2` 같은 태그를 푸시하면 서명된 릴리스 APK를 만들어 아티팩트
  (`minis-apk`)로 올리고, 태그/`release_tag`가 있으면 릴리스에 APK를 붙입니다.
- 앱 내 업데이트 확인은 이 저장소(`sampple-korea/MinisX`)의 릴리스를 봅니다.
- 빌드 변형(flavor)은 `github`(기본)과 `store` 두 가지입니다.
- 빠른 컴파일 확인은 `./gradlew :app:compileGithubDebugKotlin` 입니다.
- 단위 테스트는 `./gradlew :app:testGithubDebugUnitTest --tests "com.minisx.app.config.*"` 처럼 패키지 단위로
  돌리는 것을 권장합니다. 일부 테스트는 Robolectric이 인터넷에서 Android jar를 받아야 하거나 별도 설정 파일
  (`provider-customization.properties`)이 필요해서, 그런 환경이 없으면 원래부터 실패합니다.

---

## 저장소 구조

```
src/android/      Android 앱 (Kotlin / Compose) + JNI 네이티브 코드
src/shared/       Android 빌드와 단위 테스트가 쓰는 규칙 표·테스트 벡터
deps/             네이티브 의존성 빌드 스크립트와 소스 (PRoot, rclone-mobile 등)
docs/specs/       아키텍처·인터페이스 명세
scripts/          루트 파일시스템 준비, 개발 도구
```

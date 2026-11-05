# 공개 저장소를 빌드 러너로 쓰기

비공개 저장소의 GitHub Actions 러너는 코어 2개 / 메모리 7GB라 릴리스 빌드에 약 15분이 걸립니다.
공개 저장소의 러너는 코어 4개 / 메모리 16GB에 분 한도도 없어서 더 빠릅니다. 소스는 비공개로 두고
**빌드만 공개 저장소에서 돌리는** 방법입니다.

## 동작

1. 공개 저장소의 워크플로(`build.yml`)를 손으로 실행합니다(Actions 탭 → Run workflow).
2. 워크플로가 토큰(PAT)으로 이 비공개 저장소를 받아 `scripts/ci_build.sh`로 빌드합니다.
   모든 출력은 파일로 가고 공개 로그에는 단계마다 한 줄만 남습니다.
3. APK는 **이 비공개 저장소의 릴리스**로 올라갑니다. 공개 저장소에는 아무것도 올라가지 않습니다.
4. 빌드가 실패하면 로그 끝부분이 이 저장소의 `ci-logs` 브랜치(`logs/run-N.log`)에 저장됩니다.

## 한 번만 하는 설정

1. GitHub에서 **공개** 저장소를 하나 만듭니다(이름 예: `minisx-ci`, 빈 저장소 + README면 됩니다).
2. 토큰(PAT)을 만듭니다: Settings → Developer settings → Personal access tokens → **Fine-grained tokens**
   - Resource owner: `sampple-korea`
   - Repository access: **Only select repositories → `MinisX`** (이것만)
   - Permissions: **Contents: Read and write** (Metadata: Read는 자동)
3. 공개 저장소 Settings → Secrets and variables → Actions → New repository secret
   - Name: `MINISX_PAT`, Value: 위 토큰
4. `docs/ci-shuttle/build.yml`을 공개 저장소의 `.github/workflows/build.yml`로 올립니다
   (공개 저장소를 Claude 세션에 붙이면 Claude가 올려 줄 수 있습니다).

## 사용

공개 저장소 → Actions → "MinisX build" → Run workflow
- `ref`: 빌드할 브랜치/태그/커밋 (기본 `main`)
- `version_name`: 예 `0.2` → 릴리스 `v0.2`, APK `minis-0.2.apk` (`0.2-beta1`처럼 `-`가 있으면 프리릴리스)
- `version_code`: 직전 릴리스보다 큰 정수

## 주의

- 공개 로그에 남는 것: 저장소 이름, 단계 이름, 커밋 SHA 정도입니다. 컴파일 경고·에러·경로는 파일로만 갑니다.
- 토큰은 `MinisX` 하나에만 권한을 줬는지 확인하세요. 유출되면 그 저장소를 읽고 쓸 수 있습니다.
- 서명 키(`src/android/keystore/minis.jks`)는 이 비공개 저장소에 있고 공개 저장소로는 복사되지 않습니다.
- 이 저장소 자체의 `android.yml`은 그대로 둡니다(비상용). 분 한도가 걱정되면 `push` 트리거만 지우세요.

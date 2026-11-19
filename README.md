# OpenSLMServer

한국어 · [English](README.en.md)

OpenSLMServer는 안드로이드 휴대폰에서 LLM을 실행하고, 생성 결과를 HTTP API로 제공하는 앱입니다. 모델은 휴대폰에 저장되며 추론도 기기에서 이루어집니다. Kotlin으로 작성한 Ktor 서버가 요청을 받아 LiteRT-LM 또는 llama.cpp로 전달합니다.

## 시작하기

1. [GitHub Releases](https://github.com/mixedsider/OpenSLMServer/releases)의 테스트용 APK를 설치합니다. Actions 실행의 **Artifacts**에 있는 `android-debug-apk`를 사용하거나 아래 안내에 따라 직접 빌드할 수도 있습니다.
2. 앱에서 **Gemma 4 E2B 다운로드**를 눌러 기본 모델 파일을 받습니다. 파일 크기는 약 2.59GB입니다. [기본 모델 파일](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it.litertlm)
3. **다운로드 폴더에서 가져오기**를 눌러 받은 파일을 선택합니다. 앱 내부 저장소로 복사하므로 가져오기 전에 모델 파일 크기만큼 추가 공간이 필요합니다.
4. 모델과 가속기를 선택하고 **서버 시작**을 누릅니다. Snapdragon 888에서 처음 시험할 때는 LiteRT-LM **CPU**로 시작합니다. 초기 설정은 HTTP 포트 `8080`, 컨텍스트 `1024`토큰, 최대 출력 `256`토큰입니다.

PC에서 요청하려면 휴대폰과 PC를 같은 네트워크에 연결하고 앱에서 **같은 네트워크의 기기에서 접속 허용**을 켭니다. 이 설정은 기본으로 켜져 있으며 서버는 인증 없는 HTTP를 제공합니다. 휴대폰에서만 사용할 경우 이 설정을 끄면 서버가 `127.0.0.1`에 바인딩됩니다.

## 모델과 가속기

서버를 중지한 뒤 다른 모델 파일을 가져오거나 이미 가져온 모델을 선택합니다. 서버를 다시 시작하면 선택한 모델을 로드합니다.

| 모델 파일 | 추론 엔진 | 가속기 |
| --- | --- | --- |
| 일반 `.litertlm` | LiteRT-LM | CPU, GPU, 자동 |
| `.gguf` | llama.cpp | CPU |
| 대상 SoC용으로 사전 컴파일된 Qualcomm `.litertlm` | LiteRT-LM | 호환 기기에서 NPU |

LiteRT-LM의 **자동** 옵션은 GPU 초기화에 실패하면 CPU로 전환합니다. **Snapdragon 888(SM8350)**용 공식 Gemma 4 E2B NPU 모델은 확인되지 않았습니다. 이 기기의 기본 테스트 경로는 CPU이며 GPU 실행과 성능은 실기기에서 확인해야 합니다. 호환 Snapdragon 기기에서 NPU를 선택하려면 대상 SoC용 모델과 LiteRT Qualcomm dispatch·QAIRT 라이브러리가 포함된 APK가 필요합니다. [Qualcomm NPU 가이드](https://developers.google.com/edge/litert/next/litert_lm_npu#qualcomm_ai_engine_direct) · [실기기 테스트 계획](plan.md)

## HTTP API

아래 예제의 IP와 포트를 앱에 표시된 값으로 바꿉니다.

```bash
BASE_URL=http://192.168.1.42:8080

curl -fsS "$BASE_URL/"
curl -fsS "$BASE_URL/v1/models"

curl -fsS "$BASE_URL/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "온디바이스 추론을 한 문장으로 설명해 줘."}],
    "max_tokens": 64,
    "stream": false
  }'
```

- `GET /`는 서버 상태를, `GET /v1/models`는 현재 실행 중인 모델의 ID를 반환합니다.
- `POST /v1/chat/completions`는 텍스트 메시지를 받아 `choices[0].message.content`에 생성 결과를 반환합니다. `model`은 생략하거나 모델 목록에서 받은 ID를 사용합니다. `max_tokens`는 앱의 최대 출력 토큰 설정 이내여야 합니다.
- 현재 API는 단일 JSON 응답을 지원합니다. 대화 이력을 사용하려면 요청마다 앞선 메시지를 함께 보내고 마지막 메시지의 역할을 `user`로 지정합니다.

## 소스에서 빌드

JDK 21, Android SDK Platform 35, Build Tools 36.0.0, NDK 27.0.12077973, CMake 3.22.1이 필요합니다. APK는 `arm64-v8a`와 `x86_64`를 포함합니다. 안드로이드 최소 API 수준은 24입니다.

```bash
git clone --recurse-submodules https://github.com/mixedsider/OpenSLMServer.git
cd OpenSLMServer
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

이미 복제한 저장소에서는 `git submodule update --init --recursive`로 llama.cpp를 가져옵니다. 생성된 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 있습니다.

## CI/CD

[Android CI/CD 워크플로](.github/workflows/android.yml)는 `main` 대상 PR, `main` 푸시, 수동 실행에서 단위 테스트와 APK 빌드·서명 검증을 수행합니다. `v0.1.0` 같은 버전 태그를 푸시하면 검증된 디버그 APK를 GitHub 프리릴리스에 게시합니다. 태그 사용 예제와 동일한 키로 테스트 APK에 서명하는 방법은 [CI/CD 사용법](plan.md#github-actions-cicd)에 있습니다.

같은 휴대폰에서 이전 테스트 APK를 덮어 설치하려면 태그 빌드 간 서명 키가 같아야 합니다. 저장소의 선택적 Actions 시크릿 `ANDROID_TEST_KEYSTORE_BASE64`를 사용해 테스트 키를 재사용할 수 있습니다.

## 라이선스

OpenSLMServer는 [Apache License 2.0](LICENSE)으로 배포됩니다. 모델 파일의 이용 조건은 각 모델의 배포 페이지에서 확인하세요.

# OpenSLMServer

한국어 · [English](README.en.md)

OpenSLMServer는 안드로이드 휴대폰에서 LLM을 실행하고, 생성 결과를 HTTP API로 제공하는 앱입니다. 모델은 휴대폰에 저장되며 추론도 기기에서 이루어집니다. Kotlin으로 작성한 Ktor 서버가 요청을 받아 LiteRT-LM 또는 llama.cpp로 전달합니다.

## 시작하기

1. [GitHub Releases](https://github.com/mixedsider/OpenSLMServer/releases)의 최신 `OpenSLMServer.apk`를 설치합니다. 개발용 APK는 Actions 실행의 **Artifacts**에서 `android-debug-apk`를 받거나 아래 안내에 따라 직접 빌드할 수 있습니다.
2. 앱에서 **Gemma 4 E2B 다운로드**를 눌러 기본 모델 파일을 받습니다. 파일 크기는 약 2.59GB입니다. [기본 모델 파일](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it.litertlm)
3. **다운로드 폴더에서 가져오기**를 눌러 받은 파일을 선택합니다. 앱 내부 저장소로 복사하므로 가져오기 전에 모델 파일 크기만큼 추가 공간이 필요합니다.
4. 모델과 가속기를 선택하고 **서버 시작**을 누릅니다. Snapdragon 888에서 처음 시험할 때는 LiteRT-LM **CPU**로 시작합니다. 초기 설정은 HTTP 포트 `8080`, 컨텍스트 `1024`토큰, 최대 출력 `256`토큰입니다.

PC에서 요청하려면 휴대폰과 PC를 같은 네트워크에 연결하고 앱에서 **같은 네트워크의 기기에서 접속 허용**을 켭니다. 이 설정은 기본으로 켜져 있으며 서버는 인증 없는 HTTP를 제공합니다. 휴대폰에서만 사용할 경우 이 설정을 끄면 서버가 `127.0.0.1`에 바인딩됩니다.

서버를 시작하면 앱을 벗어나도 서버가 계속 실행됩니다. 알림을 허용하면 상단 상태 표시줄에 서버 아이콘이 표시되고, 알림창에서 접속 주소를 확인하거나 **서버 중지**를 누를 수 있습니다.

## 모델과 가속기

서버를 중지한 뒤 다른 모델 파일을 가져오거나 이미 가져온 모델을 선택합니다. 서버를 다시 시작하면 선택한 모델을 로드합니다.

컨텍스트 토큰 수를 직접 설정할 수 있습니다. GGUF는 입력한 값과 모델 메타데이터의 컨텍스트 크기 중 작은 값을 적용합니다. LiteRT-LM NPU는 모델의 컨텍스트 설정을 사용합니다.

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
- `POST /v1/chat/completions`는 기본으로 JSON을 반환합니다. `"stream": true`를 설정하면 SSE로 `chat.completion.chunk` 이벤트를 보내고 `[DONE]`으로 종료합니다. curl에서는 `-N`으로 스트리밍 출력을 볼 수 있습니다.
- `model`은 생략하거나 모델 목록에서 받은 ID를 사용합니다. `max_tokens`는 1부터 앱의 최대 출력 토큰 설정 사이로 조정됩니다. 메시지의 `content`는 문자열 또는 `{"type":"text","text":"…"}` 배열을 받으며, 첫 대화 턴 앞에 `system`과 `developer` 메시지를 둘 수 있습니다.
- 함수 정의는 `tools`로 보냅니다. 모델이 함수를 호출하면 `finish_reason`은 `tool_calls`가 되고 호출 목록은 JSON의 `message.tool_calls` 또는 SSE의 `delta.tool_calls`에 담깁니다. 클라이언트가 함수를 실행한 뒤 기존 이력과 assistant의 호출 메시지, 각 호출 ID에 대응하는 `tool_call_id`가 담긴 `tool` 메시지를 다음 요청에 보냅니다. 도구 사용이 가능한 SSE 요청은 모델 출력을 모아 함수 호출인지 판별한 뒤 전송합니다.

## 소스에서 빌드

JDK 21, Android SDK Platform 35, Build Tools 36.0.0, NDK 27.0.12077973, CMake 3.22.1이 필요합니다. APK는 `arm64-v8a`와 `x86_64`를 포함합니다. 안드로이드 최소 API 수준은 24입니다.

```bash
git clone --recurse-submodules https://github.com/mixedsider/OpenSLMServer.git
cd OpenSLMServer
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

이미 복제한 저장소에서는 `git submodule update --init --recursive`로 llama.cpp를 가져옵니다. 생성된 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 있습니다.

## CI/CD

[Android CI/CD 워크플로](.github/workflows/android.yml)는 `main` 대상 PR, `main` 푸시, 수동 실행에서 단위 테스트와 디버그 APK 빌드·서명 검증을 수행합니다. 앱 버전과 일치하는 `v1.0.0` 형식의 태그를 푸시하면 배포용 APK를 빌드하고 검증합니다. 배포 담당자는 해당 APK를 로컬에서 서명하고 GitHub Releases에 게시합니다. [CI/CD 사용법](plan.md#github-actions-cicd)

기존 디버그 APK에서 정식 APK로 전환하려면 모델 원본을 보관한 뒤 디버그 앱을 제거해야 합니다. 앱을 제거하면 앱 내부로 가져온 모델과 설정도 삭제됩니다.

## 라이선스

OpenSLMServer는 [Apache License 2.0](LICENSE)으로 배포됩니다. 모델 파일의 이용 조건은 각 모델의 배포 페이지에서 확인하세요.

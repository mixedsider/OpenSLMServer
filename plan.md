# OpenSLMServer 온디바이스 추론 계획

## Snapdragon 888 실기기 기준

- Snapdragon 888의 SoC는 SM8350이다. 첫 검증 구성은 Gemma 4 E2B IT의 일반 `.litertlm` 파일과 LiteRT-LM CPU다. 시작 설정은 컨텍스트 1,024토큰, 최대 출력 256토큰이다.
- 같은 모델에서 GPU를 별도로 시험한다. 자동 가속은 LiteRT-LM GPU 초기화 실패 시 CPU로 전환한다. 서버 상태에 실제로 선택된 가속기를 표시한다.
- SM8350용으로 공개된 Gemma 4 E2B LiteRT-LM NPU 파일을 확인하지 못했다. Snapdragon 888에서는 CPU와 GPU를 검증한다. NPU 검증은 Qualcomm 호환 모델이 있는 기기에서 별도로 진행한다.

[Qualcomm Snapdragon 888 사양](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/prod_brief_qcom_sd888_5g_1.pdf) · [Google의 Snapdragon NPU 가이드](https://developers.google.com/edge/litert/next/litert_lm_npu#qualcomm_ai_engine_direct)

## 모델 선택과 서버 검증

1. 앱에서 **Gemma 4 E2B 다운로드**를 눌러 브라우저에서 모델 파일을 받는다.
2. **다운로드 폴더에서 가져오기**로 해당 파일을 선택한다. 앱이 로컬 파일로 복사하는 동안 원본 크기만큼 여유 공간이 필요하다. 다른 `.litertlm` 또는 `.gguf`도 같은 방식으로 가져와 전환한다.
3. 화면에서 모델, 가속기, 토큰 한도, 포트, 같은 네트워크 접속 여부를 설정한 뒤 서버를 시작한다. 모델을 전환하려면 서버를 중지하고 다른 모델로 다시 시작한다.
4. 앱에 표시된 기본 URL에서 `GET /v1/models`를 호출해 모델 ID를 확인한다. `POST /v1/chat/completions`에 `messages`를 보내 텍스트 응답을 확인한다. `model`은 생략하거나 현재 ID를 사용한다.
5. 같은 요청으로 첫 답변까지 걸린 시간과 메모리, GPU 로드 여부를 확인한다. GGUF는 llama.cpp CPU로 별도로 생성해 본다.

[기본 모델 파일](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it.litertlm)

## 호환 Snapdragon 기기의 NPU 검증

- 대상 SoC에 맞는 사전 컴파일 LiteRT-LM 모델을 사용한다. 앱은 파일 이름이 `_smNNNN.litertlm`, `_qualcomm_smNNNN.litertlm`, `.qualcomm.smNNNN.litertlm`으로 끝나는 Qualcomm 변형을 인식한다.
- 호환 버전의 Qualcomm dispatch 및 QAIRT 라이브러리를 배포 권한을 확인하고 `app/src/main/jniLibs/arm64-v8a/`에 넣어 테스트 APK를 빌드한다. 모델과 런타임이 준비되면 앱에서 NPU를 선택할 수 있다.
- 기기에서 QNN/HTP 초기화 로그와 실제 토큰 생성을 함께 확인한다. 이후 지원을 확인한 SoC·모델·런타임 조합을 기록한다.

## GitHub Actions CI/CD

- `main` 대상 PR, `main` 푸시, 수동 실행에서 단위 테스트와 디버그 APK 빌드를 수행한다. 성공한 실행의 **Artifacts**에서 `android-debug-apk`를 다운로드한다. 단위 테스트 XML도 별도 아티팩트로 보관한다.
- 배포할 커밋에 `v0.1.0` 같은 태그를 붙여 푸시하면 동일한 검증을 거친 APK가 **Releases**의 프리릴리스에 게시된다.

  ```bash
  git tag v0.1.0
  git push origin v0.1.0
  ```

- 태그마다 같은 앱 서명을 사용하려면 저장소 Actions 시크릿 `ANDROID_TEST_KEYSTORE_BASE64`에 테스트용 `debug.keystore`의 Base64 값을 등록한다. 이 키는 Android 디버그 서명의 기본 별칭 `androiddebugkey`와 기본 비밀번호 `android`를 사용해야 한다. 시크릿을 비워 두면 러너가 디버그 키를 새로 만들므로, 이전 APK와 서명이 다를 때 기존 앱을 덮어 설치할 수 없다.

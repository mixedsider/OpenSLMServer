# OpenSLMServer

[한국어](README.md) · English

OpenSLMServer runs an LLM on an Android phone and serves generated text over HTTP. The phone stores the model and performs inference locally. A Ktor server written in Kotlin forwards requests to LiteRT-LM or llama.cpp.

## Quick start

1. Install a test APK from [GitHub Releases](https://github.com/mixedsider/OpenSLMServer/releases). You can also download `android-debug-apk` from **Artifacts** in a workflow run or build the APK from source.
2. In the app, tap **Gemma 4 E2B 다운로드** (Download Gemma 4 E2B) to get the default model. The file is about 2.59 GB. [Default model file](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it.litertlm)
3. Tap **다운로드 폴더에서 가져오기** (Import from Downloads) and select the file. The app copies it to internal storage, so allow additional free space equal to the size of the model file.
4. Choose a model and accelerator, then tap **서버 시작** (Start server). Start with LiteRT-LM **CPU** for the first test on a Snapdragon 888 device. The defaults are HTTP port `8080`, a `1024`-token context, and a `256`-token output limit.

To connect from a PC, put both devices on the same network and enable **같은 네트워크의 기기에서 접속 허용** (Allow access from devices on the same network). This setting is on by default and the server serves HTTP without authentication. Turn it off to bind the server to `127.0.0.1` for use on the phone only.

## Models and accelerators

To switch models, stop the server, then import a new model or select one you previously imported. Start the server again to load the selected model.

| Model file | Inference engine | Accelerator |
| --- | --- | --- |
| Standard `.litertlm` | LiteRT-LM | CPU, GPU, Auto |
| `.gguf` | llama.cpp | CPU |
| Qualcomm `.litertlm` precompiled for the target SoC | LiteRT-LM | NPU on compatible devices |

LiteRT-LM **Auto** falls back to CPU if GPU initialization fails. An official Gemma 4 E2B NPU model for **Snapdragon 888 (SM8350)** has yet to be identified. CPU is the baseline for that device, and GPU execution and performance still need to be verified on hardware. To select NPU on a compatible Snapdragon device, use a model built for its SoC and an APK that includes the LiteRT Qualcomm dispatch and QAIRT libraries. [Qualcomm NPU guide](https://developers.google.com/edge/litert/next/litert_lm_npu#qualcomm_ai_engine_direct) · [Device test plan (Korean)](plan.md)

## HTTP API

Replace the example IP address and port with the values shown in the app.

```bash
BASE_URL=http://192.168.1.42:8080

curl -fsS "$BASE_URL/"
curl -fsS "$BASE_URL/v1/models"

curl -fsS "$BASE_URL/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [{"role": "user", "content": "Explain on-device inference in one sentence."}],
    "max_tokens": 64,
    "stream": false
  }'
```

- `GET /` returns the server status. `GET /v1/models` returns the ID of the active model.
- `POST /v1/chat/completions` accepts text messages and returns generated text in `choices[0].message.content`. Omit `model` or use the ID returned by the model listing. Keep `max_tokens` within the app's output limit.
- The API currently returns a single JSON response. Include earlier messages in each request for conversation history, and end the message list with a `user` turn.

## Build from source

Use JDK 21, Android SDK Platform 35, Build Tools 36.0.0, NDK 27.0.12077973, and CMake 3.22.1. The APK includes `arm64-v8a` and `x86_64` native libraries. The minimum Android API level is 24.

```bash
git clone --recurse-submodules https://github.com/mixedsider/OpenSLMServer.git
cd OpenSLMServer
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

For an existing checkout, run `git submodule update --init --recursive` to fetch llama.cpp. The resulting APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## CI/CD

The [Android CI/CD workflow](.github/workflows/android.yml) runs unit tests, builds the APK, and verifies its signature for pull requests to `main`, pushes to `main`, and manual runs. Push a version tag such as `v0.1.0` to publish the verified debug APK as a GitHub prerelease. See the [CI/CD notes (Korean)](plan.md#github-actions-cicd) for the tag commands and how to reuse the same signing key across test APKs.

To install a newer test APK over an existing one, sign both builds with the same key. Set the optional repository Actions secret `ANDROID_TEST_KEYSTORE_BASE64` to a Base64-encoded test keystore that uses the standard Android debug alias `androiddebugkey` and password `android`.

## License

OpenSLMServer is distributed under the [Apache License 2.0](LICENSE). Check each model's distribution page for its terms of use.

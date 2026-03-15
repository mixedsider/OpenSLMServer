# 📱 온디바이스 AI API 서버 앱 개발 마스터플랜 (GPU 기반 및 NPU 확장 계획)

## 🎯 프로젝트 목표
안드로이드 스마트폰의 **GPU(Vulkan)**를 최우선으로 활용하여 LLM을 로컬에서 구동하고, Ktor 웹 서버와 대기열(Queue) 시스템을 통해 외부 기기에서 API 형태로 접근할 수 있는 안드로이드 앱 구축. (추후 NPU 동적 라우팅 지원)

## 🛠 기술 스택 (Tech Stack)
* **IDE:** Android Studio (최신 버전 권장)
* **언어:** Kotlin (UI 및 서버 로직), C/C++ (AI 추론 엔진 연동)
* **웹 서버:** Ktor (Embedded Server)
* **비동기/큐 처리:** Kotlin Coroutines & Channels
* **AI 추론 엔진 (1차):** `llama.cpp` (Vulkan 백엔드 활성화)
* **AI 추론 엔진 (추후):** ExecuTorch 또는 QNN (NPU 가속용)
* **백그라운드 유지:** Android Foreground Service, WakeLock

---

## 📅 단계별 구현 계획

### Phase 1: 기본 안드로이드 앱 및 Ktor 서버 구축
스마트폰 내부에 웹 서버를 띄우고 외부와 통신할 수 있는 네트워크 뼈대를 만듭니다.

- [ ] Android Studio에서 'Empty Views Activity' 또는 'Compose Activity'로 새 프로젝트 생성
- [ ] `build.gradle`에 Ktor 서버 종속성(Dependencies) 및 Coroutines 추가
- [ ] 앱 시작 시 백그라운드에서 Ktor 임베디드 서버(Netty) 실행 (예: 포트 `8080`)
- [ ] `/v1/chat/completions` 엔드포인트 생성 (OpenAI API 규격과 동일한 JSON 요청/응답 구조 설계)
- [ ] 동일한 Wi-Fi 환경에 있는 PC에서 Postman이나 cURL로 스마트폰 IP로 요청을 보내 통신 정상 작동 확인

### Phase 2: 코루틴 채널을 이용한 큐(Queue) 대기열 구현
하드웨어 과부하 및 메모리 부족(OOM) 크래시를 막기 위해 API 요청을 1열로 세워 순차 처리합니다.

- [ ] Kotlin `Channel` 또는 단일 스레드 `Dispatcher`를 활용한 큐(Queue) 매니저 클래스 작성
- [ ] Ktor 라우팅에서 들어오는 요청을 즉시 처리하지 않고 큐에 적재(Enqueue)
- [ ] 큐에 대기 중인 요청이 임계값(예: 5개)을 초과할 경우 `429 Too Many Requests` 에러 반환 로직 추가
- [ ] 요청 처리 지연 시 타임아웃(Timeout) 방어 로직 구현

### Phase 3: `llama.cpp` 및 Vulkan GPU 연동 (JNI 구성)
프로젝트의 심장부입니다. C++ 엔진을 안드로이드 스튜디오에 통합하고 모델을 GPU에 올립니다.

- [ ] 프로젝트에 C++ Native(NDK) 지원 추가 및 `CMakeLists.txt` 기본 셋업
- [ ] `llama.cpp` 소스 코드를 프로젝트 하위 모듈로 복사
- [ ] `CMakeLists.txt`에서 Vulkan 백엔드를 사용하도록 컴파일 옵션(`GGML_VULKAN=ON`) 활성화
- [ ] GGUF 모델 파일을 스마트폰 내부 저장소(또는 외부 저장소 앱 전용 폴더)로 복사하는 로직 구현
- [ ] Kotlin 큐에서 전달받은 텍스트 프롬프트를 JNI를 통해 C++로 넘기고, 생성된 텍스트를 다시 Kotlin으로 가져오는 브릿지(Bridge) 함수 작성

### Phase 4: 백그라운드 생존 및 시스템 관리 (System Management)
스마트폰 화면이 꺼지거나 다른 앱을 사용할 때도 서버가 죽지 않도록 방패를 씌웁니다.

- [ ] `AndroidManifest.xml`에 필요한 권한 추가 (인터넷, 포그라운드 서비스, WakeLock 등)
- [ ] Foreground Service 구현 및 실행 시 상단 상태 표시줄에 고정 알림(Notification) 표시
- [ ] 화면이 꺼져도 CPU와 GPU가 잠들지 않도록 `PowerManager.WakeLock` 및 `WifiManager.WifiLock` 적용
- [ ] 앱 종료 시 JNI를 통해 메모리에 올라간 GGUF 모델을 안전하게 해제(Release)하는 생명주기 관리 로직 추가

### Phase 5: 스트리밍(SSE) 지원 및 고도화
실제 ChatGPT API처럼 토큰 단위로 실시간 타자 효과를 낼 수 있도록 통신 방식을 업그레이드합니다.

- [ ] C++ 엔진 내 토큰 생성 콜백(Callback) 함수를 JNI로 연결하여 토큰이 나올 때마다 Kotlin으로 이벤트 전달
- [ ] Ktor 서버 응답을 단일 JSON 텍스트 반환에서 Server-Sent Events (SSE) 스트리밍 방식으로 변경
- [ ] 기기 발열(Thermal) 상태를 체크하여 온도가 높을 때 쿨링 시간을 두거나 클라이언트에게 지연을 알리는 보호 로직 추가

---

### Phase 6: [추후 계획] 하드웨어 칩셋 판별 및 NPU 동적 라우팅
앱이 범용적으로 안정화된 후, 특정 플래그십 기기에서 극한의 전력 효율을 뽑아내기 위해 NPU를 도입합니다.

- [ ] `android.os.Build.HARDWARE` 및 `SOC_MODEL`을 활용해 현재 구동 중인 스마트폰 칩셋 식별 로직 구현 (예: Snapdragon 8 Gen 2 이상 스캔)
- [ ] 확인된 칩셋이 NPU를 지원할 경우, ExecuTorch 또는 QNN 엔진 라이브러리를 동적으로 로드 (NPU 전용 `.pte` / `.bin` 모델 활용)
- [ ] NPU 로드 실패 시 또는 미지원 기기(Exynos, MediaTek 등)일 경우 기존의 `llama.cpp` (GPU/Vulkan) 엔진으로 즉시 우회(Fallback)하는 안전망(Try-Catch) 구축
- [ ] 두 가지 다른 모델 포맷(NPU용, GPU용)을 스토리지에서 효율적으로 관리하고 다운로드하는 듀얼 모델 매니저 구현

---

## 📌 주의 및 참고 사항
* **기기 연결 필수:** 에뮬레이터에서는 GPU(Vulkan) 하드웨어 가속 테스트가 불가능합니다. 반드시 실제 안드로이드 폰을 PC에 연결하여 디버깅하세요.
* **메모리(RAM) 관리:** 8GB RAM 기기 기준 3B~4B 파라미터(약 2~3GB 용량) 모델이 안정적입니다. NPU와 GPU를 동시에 메모리에 올리지 않도록 주의해야 합니다.
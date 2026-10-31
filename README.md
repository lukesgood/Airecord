# AI 회의록 (Airecord)

회의를 녹음하고, **스마트폰 안에서만** 음성을 텍스트로 바꾸고(전사) AI로 요약하는 안드로이드 앱입니다.
요약된 회의록은 카카오톡·슬랙·메일·노션·드라이브 등 다른 앱으로 바로 공유할 수 있습니다.
녹음과 텍스트는 서버로 전송되지 않습니다. 인터넷은 AI 모델을 내려받을 때만 사용합니다.

## 주요 기능

| 기능 | 내용 |
| --- | --- |
| 회의 녹음 | 16kHz 모노 WAV, 포그라운드 서비스라 화면을 끄거나 다른 앱을 써도 계속 녹음. 일시정지/재개, 알림에서 제어 |
| 오디오 가져오기 | m4a·mp3·wav 등 기존 녹음 파일을 가져와 같은 방식으로 처리 |
| 온디바이스 전사 | [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (JNI, ARM NEON 멀티스레드). 5분 단위로 처리하며 진행률 표시, 중간 결과 저장, 취소 가능 |
| 온디바이스 AI 요약 | [MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android) **GPU 백엔드** (실패 시 CPU 자동 전환). 긴 회의는 부분 요약 → 통합 방식으로 처리 |
| 회의록 형식 | **회의록 스타일(기본)**: 머리말(회의명·일시·소요 시간·장소·참석자) + 1. 회의 안건 / 2. 안건별 논의 내용 / 3. 결정 사항 / 4. 향후 조치 사항(담당자·기한) / 5. 기타 사항 및 차기 회의. 개조식 문체("~함", "~하기로 함"). 설정에서 **간단 요약** 스타일로 바꿀 수 있음. 한국어/영어 |
| 회의 정보 | 녹음 중이나 상세 화면에서 장소·참석자를 입력하면 머리말에 들어가고, 참석자 이름은 AI가 발언자·담당자 표기에 사용 |
| 공유·전송 | 요약 텍스트 공유, 요약+녹취록 공유, `.md` 파일 공유, 이메일 보내기, 녹음 파일 공유, 클립보드 복사, 기기에 파일 저장 |
| 편집 | 회의록 본문 직접 수정, 회의 정보(회의명·장소·참석자) 편집, 다시 전사/다시 요약 |
| 재생 | 녹취록 문장을 누르면 해당 위치부터 녹음 재생 |

## 사용 방법

1. **설정 → 음성 인식 모델**에서 Whisper 모델을 다운로드합니다. 한국어는 `Small` 이상을 권장하고, 최신 고사양 기기라면 `Large v3 Turbo (Q5)`가 가장 정확합니다.
2. **설정 → 요약 AI 모델**에서 MediaPipe용 `.task` LLM 파일을 가져옵니다.
   - 권장: **Gemma 3 1B IT (int4)** – [Kaggle](https://www.kaggle.com/models/google/gemma-3/tfLite) 또는 Hugging Face `litert-community/Gemma3-1B-IT`에서 라이선스에 동의한 뒤 받으세요.
   - 받은 파일을 휴대폰에 복사해 **`.task 파일 가져오기`**로 선택하거나, 다운로드 URL과 Hugging Face 액세스 토큰을 입력해 앱에서 바로 받을 수 있습니다.
   - 메모리가 넉넉한 기기(8GB 이상)라면 더 큰 모델(Gemma 3n 등 MediaPipe 호환 `.task`)을 쓰면 요약 품질이 좋아집니다.
3. 메인 화면의 **녹음 시작** → 회의 → **종료**. 녹음이 끝나면 자동으로 전사와 요약이 진행됩니다(설정에서 끌 수 있음).
4. 회의 상세 화면 오른쪽 위 **공유** 버튼으로 원하는 앱에 보냅니다.

> 팁: 설정의 **용어 힌트**에 참석자 이름, 제품명, 전문 용어를 넣으면 인식 정확도가 올라갑니다.

### 회의록 예시

```markdown
# 회의록: 3분기 마케팅 주간회의

- **일시:** 2026-10-31 14:00 (42:15)
- **장소:** 3층 대회의실
- **참석자:** 김팀장, 이대리, 박주임
- **작성:** AI 자동 작성 (기기 내 처리, 검토 필요)

## 1. 회의 안건
1. 11월 프로모션 예산 확정
2. 신규 광고 채널 검토

## 2. 안건별 논의 내용
### 2-1. 11월 프로모션 예산 확정
- 이대리: 전년 대비 15% 증액안 제시함
- 김팀장: SNS 광고 비중 확대 필요성 언급함

## 3. 결정 사항
- 11월 프로모션 예산 15% 증액하기로 함

## 4. 향후 조치 사항 (Action Items)
- [이대리] 채널별 세부 예산안 작성 (기한: 11/7)
- [박주임] 신규 광고 채널 견적 수집 (기한: 미정)

## 5. 기타 사항 및 차기 회의
- 차기 회의: 11/7(금) 14:00 예정
```

머리말은 앱이 회의 정보로 직접 채우고(AI가 날짜·참석자를 지어내지 않도록), 본문은 AI가 작성합니다.

## 구조

```
app/src/main/
├── cpp/                     whisper.cpp JNI 브릿지 (CMake가 빌드 시 whisper.cpp를 받아옴)
└── java/com/airecord/app/
    ├── core/                순수 Kotlin 로직 (WAV 입출력, 리샘플러, 텍스트 분할, 프롬프트, 내보내기)
    ├── data/                회의 데이터 모델·저장소(JSON + WAV, 앱 내부 저장소), 설정
    ├── audio/               외부 오디오 파일 디코딩 → 16kHz WAV
    ├── stt/                 WhisperLib(JNI), WhisperTranscriber
    ├── llm/                 LlmSummarizer (MediaPipe, GPU→CPU 폴백, map-reduce 요약)
    ├── models/              모델 다운로드/가져오기/관리
    ├── service/             MeetingService(녹음 + 처리 큐, 포그라운드 서비스), MeetingProcessor
    ├── share/               공유·전송 (Share sheet, FileProvider, 이메일, 클립보드, 저장)
    └── ui/                  Jetpack Compose 화면 (목록, 녹음, 상세, 설정)
```

처리 흐름:

```
녹음(AudioRecord) ─► audio.wav ─► whisper.cpp (5분 단위) ─► 녹취록 segments
                                                          │
                                  MediaPipe LLM (GPU) ◄───┘
                                     ├ 짧으면: 바로 회의록 생성
                                     └ 길면: 부분 요약 → (압축) → 최종 회의록
                                                          │
                                         공유 / 이메일 / 파일 저장 ◄┘
```

## 빌드

요구 사항: JDK 17, Android SDK 35, NDK `27.2.12479018`, CMake `3.22.1` (Android Studio Ladybug 이상이면 자동 설치).

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 순수 로직 단위 테스트
```

- 첫 빌드 때 CMake가 GitHub에서 whisper.cpp(`gradle.properties`의 `whisper.version`)를 내려받습니다. 오프라인이라면 `whisper.sourceDir=/path/to/whisper.cpp`를 지정하세요.
- GitHub Actions(`.github/workflows/android.yml`)가 푸시할 때마다 테스트를 돌리고 APK를 빌드해 아티팩트로 올립니다.
- 지원 ABI: `arm64-v8a`(실기기), `x86_64`(에뮬레이터). 16KB 페이지 크기 기기도 지원합니다.

### GPU 사용에 대해

- **요약(LLM)**: MediaPipe GPU 백엔드(OpenCL/OpenGL)로 실행됩니다. 기기가 지원하지 않으면 CPU로 자동 전환되며, 상세 화면 하단에 실제 사용된 백엔드(GPU/CPU)가 표시됩니다.
- **전사(Whisper)**: 기본값은 ARM NEON 최적화 CPU 실행입니다. `gradle.properties`에서 `whisper.vulkan=true`로 바꾸면 ggml Vulkan 백엔드를 넣어 빌드합니다(실험 기능: 호스트에 Vulkan SDK/`glslc`가 있어야 하고, 기기 GPU 드라이버에 따라 CPU보다 느리거나 불안정할 수 있음).

## 개인정보

- 녹음, 녹취록, 요약은 앱 내부 저장소(`files/meetings/`)에만 저장되며 백업(`allowBackup`)도 꺼져 있습니다.
- 공유는 사용자가 직접 선택한 앱으로만 전달됩니다.

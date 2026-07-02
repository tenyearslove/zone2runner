# llm-verify — 온디바이스 LLM 실기기 검증 앱 (adr-007)

S26 Ultra에서 **Gemini Nano(ML Kit GenAI Prompt API)**가 실제로 되는지 확인하는 최소 안드로이드 앱.
adr-007의 검증 계획을 실행한다.

## 무엇을 하나
1. 기기/SoC 정보 표시
2. `Generation.getClient().checkStatus()`로 **가용성(FeatureStatus)** 조회
3. 필요 시 모델 **다운로드**
4. 코칭형 프롬프트로 **텍스트 생성 + 지연(콜드/웜) 측정**

## 검증 결과 (2026-07-02, Exynos S26 SM-S942B / Android 16)

- **FeatureStatus AVAILABLE** — 모델(**~4GB**) 다운로드 후 사용 가능. Exynos에서도 동작.
- **warm 생성 ~2.0~2.1초** (목표 ≤2~3초 통과) / cold ~3.2초(최초 1회)
- **코칭 방향 정확**(감속) + 자연스러운 문장
- **TTS 한국어 음성 출력 정상** → LLM→TTS **end-to-end 동작 확인**
- **제약(ErrorCode 30)**: Gemini Nano 생성은 **앱 포그라운드에서만** 허용. 다운로드 중 앱을 나가면 백그라운드 전환되어 진행률 추적 끊김.
- **잔여**: 오프라인(비행기모드), Snapdragon Ultra 재확인
- → 결론: **Plan A(Gemini Nano) 채택** (adr-007)

## 빌드 / 실행
- **Android Studio**: `llm-verify/` 폴더 열기 → S26 Ultra 연결 → Run. (권장)
- **CLI**: `cd llm-verify && ./gradlew :app:assembleDebug` → APK: `app/build/outputs/apk/debug/app-debug.apk`
  - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- 요구: JDK 17, Android SDK(compileSdk 35), minSdk 26.

## 확인할 것 (통과 기준)
- FeatureStatus가 AVAILABLE(또는 다운로드 후 AVAILABLE)인가 → 사용 가능 여부
- 생성 텍스트가 방향(감속) 맞게 나오는가 → 기저 품질
- **웜 지연 ≤ 2~3초** (TTS 포함 end-to-end 5초 예산, QA4)
- **비행기 모드에서도 동작** → 오프라인(온디바이스) 확인

## 결과별 다음 단계 (adr-007)
- 통과 → Plan A(Gemini Nano) 확정
- UNAVAILABLE/지연 초과 → Plan B(자체탑재 Gemma+LiteRT-LM) 검증 → 그래도 불가 시 Plan C(서버)

## 구성
- `com.google.mlkit:genai-prompt:1.0.0-beta2` (Kotlin 2.2.0, AGP 8.7.2, Gradle 8.9)
- `MainActivity.kt`(UI), `GeminiNanoProbe.kt`(검증 로직)

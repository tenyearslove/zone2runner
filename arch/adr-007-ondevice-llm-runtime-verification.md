# ADR-007: On-device LLM 실현 방식 및 실기기 검증

- **날짜**: 2026-07-02
- **상태**: Accepted (Exynos S26 실기기 검증 통과. 오프라인/Ultra 재확인은 잔여)
- **결정자**: 성시원
- **보고서 매핑**: 설계 - Architectural Decision (DP2 후속)

---

## 맥락

DP2(adr-002)는 코칭 문장 생성을 On-device LLM으로 하되 "구체 런타임은 구현 시 확정"으로 유보했다. 그러나 **S26 Ultra에서 온디바이스 LLM이 실제로 되는지 미검증**이다. QA6 수행효율성(5초 이내)과 러닝 중 네트워크 불안정/프라이버시 때문에 온디바이스가 선호되지만, 안 되면 자체 탑재나 서버로 가야 한다. 진행 전에 실현 방식을 정하고 검증한다.

## 리서치 요약 (2026 중반)

- **Gemini Nano + AICore**: 안드로이드 시스템 서비스(AICore)가 공유 온디바이스 모델을 관리. 앱은 ML Kit GenAI로 접근.
- **ML Kit GenAI "Prompt API"**: 커스텀 프롬프트로 자유 텍스트 생성 지원(요약/교정 등 정형 작업 외). → 코칭멘트 생성에 적합.
- **기기 지원**: Galaxy S26 시리즈(Ultra/Plus/Base) 지원 목록에 포함. Snapdragon/Tensor/Dimensity 플랫폼.
- **자체 탑재 대안**: LiteRT-LM Android(구 MediaPipe LLM Inference, 현재 유지보수 모드)로 Gemma 등 소형 모델 번들 실행.
- **서버 대안**: Gemini API(클라우드) — 어디서나 되지만 오프라인/프라이버시 상충.

## 대안 비교

| 기준 | A. Gemini Nano (ML Kit Prompt API) | B. 자체 탑재 (Gemma + LiteRT-LM) | C. 서버 (클라우드 LLM) |
|------|------|------|------|
| S26 Ultra 지원 | 지원 목록 O (검증 필요) | 가능(칩 성능 의존) | 항상 |
| 오프라인 | O | O | X |
| 프라이버시 | O (온디바이스) | O | X (데이터 전송) |
| 앱 크기 | 작음 (OS 공유 모델) | 큼 (모델 번들 수백MB~) | 작음 |
| 지연(QA6 수행효율성) | 낮음(하드웨어 가속) | 중(기기 의존) | 네트워크 의존 |
| 개발 부담 | 낮음(고수준 API) | 중~높음(런타임/모델 관리) | 낮음 |
| 리스크 | availability 게이팅 | 성능/발열/배터리 | 오프라인 미충족 |

## 결정 (검증 조건부)

- **확정 = A (Gemini Nano via ML Kit Prompt API)**. Exynos S26 실기기 검증 통과(AVAILABLE, warm ~2초, 방향 정확). 오프라인/프라이버시/저지연 부합.
- **폴백 = B (자체 탑재, Gemma+LiteRT-LM)**. 잔여 확인(오프라인/Ultra)에서 문제 시. 오프라인 유지.
- **최후 = C (서버)**. A/B 모두 불가 시. QA(오프라인/프라이버시)와 상충하므로 지양.

잔여 확인: 오프라인(비행기모드) 동작, 사용자 Snapdragon Ultra 재확인. (문제 없으면 A 그대로)

## 실기기 검증 계획 (S26 Ultra)

**목표**: A가 실제로 되는지, QA6 수행효율성 예산에 맞는지 확인.

1. **가용성 확인**: 최소 앱에서 ML Kit GenAI Prompt API의 feature 상태 조회(AVAILABLE / DOWNLOADABLE / UNAVAILABLE). 필요 시 experimental access 등록 여부 확인. (기기 지역/칩셋 Snapdragon vs Exynos, OS 버전 주의)
2. **동작 확인**: 코칭형 프롬프트 1개 실행 → 출력 텍스트 확인 (예: "심박 초과, 오르막, 더운 날씨 → 페이스 낮추라는 한 문장").
3. **측정**:
   - 지연: 콜드(첫 호출) vs 웜(이후) 각각. TTS 포함 end-to-end 5초 예산과 대조 (QA6 수행효율성).
   - 오프라인: 비행기 모드에서 동작 여부.
   - 모델 준비: 최초 다운로드 크기/시간.
   - 출력 품질: 방향(가속/감속) 일관성 (규칙 가드로 보정 가능하나 기저 품질 확인).
4. **통과 기준**: 오프라인 동작 + 웜 지연이 TTS 포함 5초 예산 내(대략 LLM ≤ 2~3초) + 자유 프롬프트 사용 가능.
   - 통과 → **A 확정**. 실패(가용성/지연) → **B 검증**(Gemma 소형 모델 LiteRT-LM 탑재 후 동일 측정) → 그래도 불가 시 **C**.

## 실기기 검증 결과 (2026-07-02, Exynos S26 SM-S942B, Android 16/SDK36)

llm-verify 앱(ML Kit Prompt API `com.google.mlkit:genai-prompt:1.0.0-beta2`)으로 측정.

| 항목 | 결과 | 판정 |
|------|------|:---:|
| FeatureStatus | AVAILABLE (다운로드 후) | O |
| warm 생성 지연 | ~2.0~2.1초 (2000/2023/2105ms) | O (≤2~3초) |
| cold 생성 지연 | ~3.1~3.3초 (최초 1회) | 참고 |
| 코칭 품질/방향 | "페이스 낮추라" 자연스러운 감속 안내 = 방향 정확 | O |
| TTS (한국어) | init OK(KOREAN), 생성 문장 음성 출력 정상 | O |
| **end-to-end (LLM→TTS)** | warm 생성(~2초) → TTS 음성까지 동작 확인 | O |
| 오프라인(비행기모드) | 미확인 | 잔여 |
| Snapdragon Ultra | 미확인(Exynos 통과로 가능성 매우 높음) | 잔여 |

- **Exynos S26에서도 Gemini Nano 동작 확인** → 지원 목록(Snapdragon/Tensor/Dimensity)에 명시 안 된 Exynos도 됨.
- **모델 크기 ~4GB** (측정: 4023.8MB). 최초 1회 다운로드, 진행률(%) 표시됨. 저장공간/데이터 UX에 반영 필요.
- 앱을 나가면 백그라운드 다운로드로 전환되어 진행률 추적 끊김 → **다운로드 UX는 포그라운드 관찰 권장**(spec-005 반영).
- 세션 시작 시 warmup 후 반복 호출은 warm(~2초)로 동작 → QA6 수행효율성 5초 예산 내(LLM 2초 + TTS 여유 3초).

### ⚠️ 리스크: 포그라운드 전용 (ErrorCode 30)
- Gemini Nano 생성/warmup은 **앱이 포그라운드일 때만** 허용됨(`GenAiException ErrorCode 30: Background usage is blocked`). 백그라운드에서 호출 시 차단.
- **실사용 시나리오 리스크**: 러닝 중 폰을 주머니에 넣고 화면을 끄면 앱이 백그라운드가 되어 코칭 생성이 막힐 수 있음. → **확인 필요**: (a) 포그라운드 서비스로 우회 가능한지, (b) 화면 켜짐/워치 화면 활용, (c) 불가 시 폴백(자체탑재 B는 이 제약 없음일 수 있음). 이 시나리오 검증을 spec-005/후속에 과제로 남긴다.

## 결과 / 영향

- spec-005(LLM 코칭)의 "LLM 런타임"이 본 검증으로 확정된다.
- 최초 실행 모델 다운로드 진행 화면 + 다운로드 중 규칙/템플릿 코칭 graceful degradation 필요(spec-005 §모델 준비).
- PoC 단계에서는 LLM 호출을 인터페이스(`CoachingTextGenerator`)로 추상화해, A/B/C를 교체 가능하게 둔다(Mock 포함). → QA5 테스트가능성 및 리스크 격리.
- 검증은 최소 테스트 앱(Kotlin, ML Kit GenAI Prompt API)으로 수행. 코드는 별도 작성.

## Sources
- [Gemini Nano | Android Developers](https://developer.android.com/ai/gemini-nano)
- [ML Kit GenAI APIs overview](https://developers.google.com/ml-kit/genai)
- [Get started with Prompt API | ML Kit](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [LLM Inference / LiteRT-LM guide for Android | Google AI Edge](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)

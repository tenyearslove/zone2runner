# ADR-007: On-device LLM 실현 방식 및 실기기 검증

- **날짜**: 2026-07-02
- **상태**: Proposed (S26 Ultra 실기기 검증 후 확정)
- **결정자**: 성시원
- **보고서 매핑**: 설계 - Architectural Decision (DP2 후속)

---

## 맥락

DP2(adr-002)는 코칭 문장 생성을 On-device LLM으로 하되 "구체 런타임은 구현 시 확정"으로 유보했다. 그러나 **S26 Ultra에서 온디바이스 LLM이 실제로 되는지 미검증**이다. QA4(5초 이내)와 러닝 중 네트워크 불안정/프라이버시 때문에 온디바이스가 선호되지만, 안 되면 자체 탑재나 서버로 가야 한다. 진행 전에 실현 방식을 정하고 검증한다.

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
| 지연(QA4) | 낮음(하드웨어 가속) | 중(기기 의존) | 네트워크 의존 |
| 개발 부담 | 낮음(고수준 API) | 중~높음(런타임/모델 관리) | 낮음 |
| 리스크 | availability 게이팅 | 성능/발열/배터리 | 오프라인 미충족 |

## 결정 (검증 조건부)

- **1순위 = A (Gemini Nano via ML Kit Prompt API)**. S26 Ultra 지원 목록에 있고 오프라인/프라이버시/저지연에 부합. 단 **아래 실기기 검증 통과가 조건**.
- **폴백 = B (자체 탑재, Gemma+LiteRT-LM)**. A가 availability/지연에서 실패 시. 오프라인 유지.
- **최후 = C (서버)**. A/B 모두 불가 시. QA(오프라인/프라이버시)와 상충하므로 지양.

상태 Proposed — 검증 결과로 확정.

## 실기기 검증 계획 (S26 Ultra)

**목표**: A가 실제로 되는지, QA4 예산에 맞는지 확인.

1. **가용성 확인**: 최소 앱에서 ML Kit GenAI Prompt API의 feature 상태 조회(AVAILABLE / DOWNLOADABLE / UNAVAILABLE). 필요 시 experimental access 등록 여부 확인. (기기 지역/칩셋 Snapdragon vs Exynos, OS 버전 주의)
2. **동작 확인**: 코칭형 프롬프트 1개 실행 → 출력 텍스트 확인 (예: "심박 초과, 오르막, 더운 날씨 → 페이스 낮추라는 한 문장").
3. **측정**:
   - 지연: 콜드(첫 호출) vs 웜(이후) 각각. TTS 포함 end-to-end 5초 예산과 대조 (QA4).
   - 오프라인: 비행기 모드에서 동작 여부.
   - 모델 준비: 최초 다운로드 크기/시간.
   - 출력 품질: 방향(가속/감속) 일관성 (규칙 가드로 보정 가능하나 기저 품질 확인).
4. **통과 기준**: 오프라인 동작 + 웜 지연이 TTS 포함 5초 예산 내(대략 LLM ≤ 2~3초) + 자유 프롬프트 사용 가능.
   - 통과 → **A 확정**. 실패(가용성/지연) → **B 검증**(Gemma 소형 모델 LiteRT-LM 탑재 후 동일 측정) → 그래도 불가 시 **C**.

## 실기기 관찰 (2026-07-02, 진행 중)

- 정규 **S26 (Exynos s5e9965, Android 16/SDK36)** 에서 llm-verify 앱 실행 → FeatureStatus가 **DOWNLOADABLE**(UNAVAILABLE 아님) → **Exynos에서도 Gemini Nano 지원 신호**. 모델 다운로드 진행 확인.
- 최초 1회 모델 다운로드가 수백 MB/수 분 소요 → **최초 실행 다운로드 UX 필요**(spec-005에 반영).
- 남은 확인: 다운로드 완료 후 warm 생성 지연(≤2~3초), 오프라인 동작, 그리고 사용자 Ultra(Snapdragon)에서의 재확인.

## 결과 / 영향

- spec-005(LLM 코칭)의 "LLM 런타임"이 본 검증으로 확정된다.
- 최초 실행 모델 다운로드 진행 화면 + 다운로드 중 규칙/템플릿 코칭 graceful degradation 필요(spec-005 §모델 준비).
- PoC 단계에서는 LLM 호출을 인터페이스(`CoachingTextGenerator`)로 추상화해, A/B/C를 교체 가능하게 둔다(Mock 포함). → QA5 및 리스크 격리.
- 검증은 최소 테스트 앱(Kotlin, ML Kit GenAI Prompt API)으로 수행. 코드는 별도 작성.

## Sources
- [Gemini Nano | Android Developers](https://developer.android.com/ai/gemini-nano)
- [ML Kit GenAI APIs overview](https://developers.google.com/ml-kit/genai)
- [Get started with Prompt API | ML Kit](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [LLM Inference / LiteRT-LM guide for Android | Google AI Edge](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)

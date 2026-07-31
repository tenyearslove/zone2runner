# 리서치: Gemini Nano 온디바이스 기능 조사 (LLM 외 활용 가능성)

- **상태**: 리서치 노트(정보용) — 결정은 후속 ADR/spec에서
- **날짜**: 2026-07-14
- **관련**: `arch/adr-025`(AI≠NN, 표현=LLM), `spec/spec-005`(LLM 코칭), `spec/spec-023`(설명 서비스), CLAUDE.md(무클라우드/온디바이스)
- **방법**: 별도 리서치 에이전트(WebSearch/WebFetch)로 공식 문서 위주 조사(2024~2026). 아래 각 절 끝에 출처.

## 목적

우리는 이미 Gemini Nano를 **온디바이스 LLM**으로 써서 코칭 문구를 표현한다(RuleCoach가 방향을 확정 → LLM은 표현만, DirectionGuard 가드레일 + 템플릿 폴백). "Nano에 LLM 말고 다른 기능이 있지 않나"라는 질문에 답하기 위해, Nano/AICore가 제공하는 **자유 텍스트 생성 외 기능**과 우리 앱 적용성을 조사했다.

## TL;DR (한 줄 결론)

- **대부분은 "같은 온디바이스 LLM이 특정 과제를 수행"하는 것**이지 별도 모델이 아니다. 구글은 이를 **ML Kit GenAI API**(Summarization/Proofreading/Rewriting/Image Description/Prompt/Speech Recognition)로 제공하며 전부 **AICore** 위 Gemini Nano로 돈다.
- 진짜 "LLM 아님"(안전 분류기, 임베딩)은 대체로 **AICore 내부라 서드파티에 API로 노출 안 됨**.
- **우리 앱에 걸리는 두 하드 제약**:
  1. **온디바이스 구조화(JSON) 출력은 아직 정식 출시 전**(I/O 2026 "예정" 발표). → "규칙이 정하고 LLM은 표현만" 패턴이 여전히 맞다.
  2. **Gemini Nano/AICore는 Wear OS 미지원.** "Gemini on Wear OS"는 클라우드 어시스턴트다. → 모든 Nano 기능은 **폰**에서, 워치는 센서/뷰어로.

## 1. ML Kit GenAI API (Nano 기반 과제형 API)

전부 온디바이스(AICore), **API 26+**, **잠금 부트로더**, 지원 기기 한정, **앱이 포그라운드일 때만** 추론(앱별 연산/배터리 쿼터 — 초과 시 `BUSY`/배터리쿼터 에러). 런타임에 `checkFeatureStatus()`로 `UNAVAILABLE/DOWNLOADABLE/DOWNLOADING/AVAILABLE` 확인 후 `download()`(가중치는 AICore가 관리, 앱이 번들 안 함).

| API | 하는 일 | 입력 → 출력 | 옵션/언어 | 안정성 |
|-----|---------|-------------|-----------|--------|
| **Summarization** | 글/대화를 불릿 요약 | 텍스트(≤약 3000단어) → 1/2/3 불릿 | 영어/일본어/**한국어** | Beta |
| **Proofreading** | 짧은 글 맞춤법/문법 교정 | 짧은 텍스트 → 교정본 | 짧은 채팅형 | Beta |
| **Rewriting** | 짧은 글을 **다른 톤**으로 재작성 | 짧은 텍스트 → 재작성본 | 톤: Elaborate/Emojify/Shorten/Friendly/Professional/Rephrase, 7개 언어(EN/JA/FR/DE/IT/ES/**KO**) | Beta |
| **Image Description** | 이미지 설명(alt-text) | `Bitmap` → 짧은 설명 | **영어만** | Beta |
| **Prompt** | 자유 프롬프트(우리 현행) | 텍스트(+이미지) → 텍스트 | `temperature/topK/maxOutputTokens/candidateCount/seed`, **스트리밍**, **단일 턴** | Beta |
| **Speech Recognition** | 오디오 → 텍스트 전사 | 오디오 → 텍스트 | Basic(API31+)/Advanced(Pixel10) | Alpha |

출처: [ML Kit GenAI overview](https://developers.google.com/ml-kit/genai), [Summarization](https://developers.google.com/ml-kit/genai/summarization/android), [Rewriting](https://developers.google.com/ml-kit/genai/rewriting/android), [Image Description](https://developers.google.com/ml-kit/genai/image-description/android), [Prompt](https://developers.google.com/ml-kit/genai/prompt/android/get-started), [Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android), [블로그 2025-05](https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html), [블로그 2025-08](https://android-developers.googleblog.com/2025/08/the-latest-gemini-nano-with-on-device-ml-kit-genai-apis.html)

> 주의: I/O 2026에서 Prompt API를 "production-ready"로 표현했으나 실제 아티팩트는 `1.0.0-beta2`로 표준 beta 고지("SLA/하위호환 보장 없음")를 그대로 단다. **전부 beta로 취급.**

## 2. 구조화 출력/함수 호출/AICore 고급

- **Prompt API** = 유연한 하위 표면(LanguageModel 성격). 자연어+선택적 이미지, `GenerationConfig` 튜닝, **스트리밍** 지원. **단일 턴**(내장 멀티턴 없음 — 컨텍스트 직접 이어붙임). **system role 전용 필드 없음**(지시문을 프롬프트에 선행 삽입 — 우리 "RuleCoach 방향 주입"이 여기 해당).
- **온디바이스 JSON/구조화 출력 = 현행 beta에 없음.** ML Kit GenAI **Structured Output API**가 **I/O 2026(2026-05)에 "예정"으로 발표**(스키마 강제 → 취약한 정규식 파싱 축소, 제약 디코딩이 훨씬 빠르다는 보고). 그러나 2026 중반 기준 프리뷰/예정, GA 아님.
  - 혼동 주의: **Chrome**의 Prompt API는 이미 JSON 스키마 출력 지원 — 그건 데스크톱/Chrome 경로이지 **Android ML Kit 경로가 아님.** 동등하다고 가정 금지.
- **함수 호출/툴**: ML Kit Prompt API 기능 아님. Android의 툴 호출은 별도 **AppFunctions/"Android MCP"**(I/O 2026 실험 프리뷰)로, 코칭 앱엔 과한 통합.
- **AICore 레벨 진전(I/O 2026)**: **Prefix Caching**(고정 프롬프트 프리픽스의 LLM 상태 재사용 — 우리 고정 코칭 프리앰블에 직접 유효), **LiteRT-LM**(자체 소형 파인튜닝 모델), **Firebase AI Logic Hybrid Inference**(`ONLY_ON_DEVICE`/`PREFER_ON_DEVICE` — 무클라우드 규칙엔 `ONLY_ON_DEVICE`만 부합).

출처: [Prompt get-started](https://developers.google.com/ml-kit/genai/prompt/android/get-started), [Prompt API 블로그 2025-10](https://android-developers.googleblog.com/2025/10/ml-kit-genai-prompt-api-alpha-release.html), [I/O 2026 라운드업](https://developer.android.com/blog/posts/top-ai-on-android-updates-for-building-intelligent-experiences-from-google-i-o-26), [Chrome 구조화 출력(다른 플랫폼)](https://developer.chrome.com/docs/ai/structured-output-for-prompt-api)

## 3. 멀티모달(이미지/오디오 입력)

- **이미지 입력: 가능**(온디바이스). Prompt API가 이미지+텍스트(`ImagePart`+`TextPart`) 수용, Image Description은 `Bitmap`. **"Gemini Nano with Multimodality"(nano-2, Pixel 9 최초)** 부터. 이후 확대.
- **오디오 입력: 가능하나 좁고 미성숙** — Speech Recognition(alpha) 오디오→텍스트 전사만. 프롬프트 내 일반 오디오 이해는 서드파티 미노출.
- **Nano 버전**: nano-1(텍스트) → nano-2(멀티모달, Pixel 9) → nano-v3(Pixel 10, Gemma 3n 계열) → Gemini Nano 4(I/O 2026 개발자 프리뷰). 멀티모달일수록 **최신 플래그십 기기에 가장 종속**.

출처: [Google Store — Nano 멀티모달](https://store.google.com/intl/en/ideas/articles/gemini-nano-offline/), [Android Authority — 기능/기기](https://www.androidauthority.com/gemini-nano-features-devices-3490062/), [블로그 2024-10](https://android-developers.googleblog.com/2024/10/gemini-nano-experimental-access-available-on-android.html)

## 4. 기기/런타임 제약

- **칩셋**: MediaTek Dimensity, Qualcomm Snapdragon, Google Tensor(AICore 경유).
- **폰(대표, 확대 중)**: Pixel 8+/9/10, Galaxy S24/S25, Z Fold6/Flip6, 그 외 2024~2026 플래그십 다수. **기능 지원은 기기마다 다름**(같은 지원 기기라도 Summarization은 되고 최신 멀티모달은 안 되는 식).
- **AICore 필수** — 모델 저장/갱신/추론 담당, 앱은 가중치 미탑재. Nano는 수 GB급 온디바이스 모델이라 플래그십 한정.
- **API 26+, 잠금 부트로더만**(루팅/언락 미지원), **포그라운드 전용 추론**, 앱별 연산/배터리 쿼터.
- **점진적 저하**: `checkFeatureStatus()` + 다운로드 상태 처리 후 사용, 비AI 폴백 유지(우리 템플릿 폴백 유지 = 정답).
- **★ Wear OS = 온디바이스 Nano 미지원.** AICore/Nano는 폰 기능. "Gemini on Wear OS"는 클라우드 어시스턴트(워치 RAM 제약). → Nano 기능은 **페어링된 폰**에서, 워치는 센서/UI로.

출처: [Android Police — 지원 폰](https://www.androidpolice.com/what-phones-support-gemini-nano/), [Gemini Nano dev(AICore/Private Compute)](https://developer.android.com/ai/gemini-nano), [Gemini on Wear OS(클라우드)](https://blog.google/products/wear-os/gemini-wear-os-watches/)

## 5. 진짜 "LLM 아님"인 것 (정직한 정리)

- **안전/독성 분류기: 존재하나 내부.** AICore가 학습/LoRA/입출력 분류기(욕설/민감정보/공격성)로 안전을 자동 적용하지만, **"이 텍스트 독성 점수 매겨줘" 같은 공개 API로 노출 안 함.** 즉 Nano 출력엔 안전 필터가 공짜로 걸리지만, 우리가 DirectionGuard에 붙일 **독립 독성 API는 없음.**
- **온디바이스 임베딩: 능력은 있으나 공식 ML Kit GenAI 임베딩 API 부재.** 필요하면 별도 LiteRT/MediaPipe 임베더가 현실적.
- **나머지(요약/교정/재작성/이미지설명/Prompt/음성)**: 전부 **LLM이 과제를 수행**(LoRA 어댑터+프롬프트 스캐폴딩). Speech Recognition만 별도 전사 파이프라인에 가장 가깝다.

출처: [Gemini Nano 안전 계층](https://developer.android.com/ai/gemini-nano), [InfoQ — ML Kit GenAI/safety](https://www.infoq.com/news/2025/06/google-mlkit-genai-gemini-nano/)

## 6. 클래식 ML Kit (온디바이스지만 Nano 아님)

Nano 이전부터 있고 **거의 모든 폰(API 21+, AICore 불필요)**에서 작은 과제 모델로 돈다. 텍스트 코칭 앱에 유용한 것:

| 클래식 ML Kit | 러닝 앱 활용 |
|---------------|--------------|
| **Language Identification** | 사용자 언어 감지 → 코칭 템플릿/톤 라우팅 |
| **Translation**(58개 언어, 오프라인 팩) | 코칭 문구 오프라인 현지화 |
| **Entity Extraction**(날짜/시간/주소 등) | 사용자 입력 메모/목표에서 값 추출 |
| **Text Recognition v2(OCR)** | 트레드밀 표시/대회 배번 사진 숫자 읽기 |
| Smart Reply / 비전(바코드/포즈/얼굴 등) | 대체로 무관(포즈=폼 분석 가능하나 스코프 밖) |

이건 **모든 사용자 기기(저가 폰 포함)**에서 돌아야 할 때의 정답. 단, 클래식 ML Kit도 **폰/태블릿 SDK**(워치 런타임 아님).

출처: [ML Kit home](https://developers.google.com/ml-kit), [ML Kit NLP 블로그](https://android-developers.googleblog.com/2019/04/ml-kit-expands-into-nlp-with-language.html), [Text Recognition v2](https://developers.google.com/ml-kit/vision/text-recognition/v2)

## 우리 앱 적용성 (관측 우선/무클라우드/폰=Nano/워치=센서 전제)

| 기능 | 적합도 | 우리 앱 구체 용도 |
|------|:---:|-------------------|
| **Rewriting(톤)** | **높음** | RuleCoach가 확정한 문장의 페르소나/톤 변환(Friendly/Professional/Shorten/Elaborate). **내용은 규칙이 고정, 톤만 변경** → "LLM은 표현만 + 가드레일" 모델을 그대로 강화, 자유 프롬프트보다 결정론적. Shorten은 워치/알림용 짧은 문구에 이상적. 한국어 지원. |
| **Summarization** | **높음** | 세션 종료 리포트 내러티브: 파생 관측(드리프트/EF/GAP/존 체류)을 1~3불릿 요약. "세밀한 관측 리포트" 제1원칙에 부합(실제 도출값만 먹이고 LLM은 표현만). 한국어 지원, 세션당 1회 배치. |
| **Prompt(현행)** | **높음(이미 사용)** | 표현 엔진 유지. **Prefix Caching**로 고정 프리앰블 지연/배터리 절감, `temperature`↓/`maxOutputTokens`↓로 간결/결정론적. |
| **구조화 출력(JSON)** | **중(아직 불가)** | LLM이 파싱 대신 검증된 객체(`{tone,message,emphasis}`) 반환하면 가드레일이 깔끔. **막힘: 온디바이스 구조화 출력 "예정"(I/O 2026), beta 미포함.** → 채택 아닌 **감시 대상 ADR**. 그전까지 템플릿 파싱+가드레일 유지. |
| **읽기 수준 단순화** | 중 | 전용 API 없음 → Rewriting(Shorten/Friendly)이나 프롬프트로 근사. 가이드 "중학생 눈높이" 목표에 부합하나 자체 프롬프트+가드레일 필요. |
| **Language ID/Translation/Entity(클래식)** | 중 | 오프라인 현지화/목표 파싱 시 **모든 기기**에서 동작(Nano와 달리). Summarization이 EN/JA/KO 한정이라 다국어 확장 시 실용 경로. |
| **Image Description** | 낮음 | 경로 지도/차트 이미지를 alt-text로 설명할 때만. 우리는 수치 텔레메트리 중심 + **영어만**. 스킵. |
| **Proofreading** | 낮음 | 기계 생성 짧은 문구라 교정할 게 적음. 사용자 저널링 추가 시에만. |
| **온디바이스 독성/안전 API** | 낮음/없음 | AICore 내장 안전 필터는 공짜로 걸리나 **호출 가능한 독립 API 없음.** 규칙/템플릿 가드레일이 진짜 안전층. |
| **Speech Recognition** | 낮음 | 음성 메모→텍스트("지금 느낌?") 가능하나 alpha/폰 전용, 현재 오디오 입력 없음. 보류. |
| **온디바이스 임베딩** | 낮음 | "비슷한 과거 세션 찾기" 가능하나 공식 Nano 임베딩 API 없음(별도 임베더 필요). 스코프 밖. |

**우리가 가정 못 하거나 입력이 없는 것**: 이미지 설명/멀티모달은 이미지 필요(우리는 수치), 음성인식은 오디오 필요(없음), 모든 Nano 기능은 AICore 플래그십 폰 필요(상당수 폴백), **워치에서 도는 Nano는 없음**.

## 정직한 한계

- **Beta 변동**: 관련 API 전부 beta(Prompt/Summarization/Rewriting/Proofreading/Image Description) 또는 alpha(Speech). 구조화 출력은 "예정". 시그니처 변경 예상 → 버전 고정 + **`CoachTextEngine` 포트로 격리**(우리 `RunSource` DIP와 같은 본능).
- **가용성 게이팅 현실**: `checkFeatureStatus()` + 다운로드 상태 처리 필수, 지원 폰끼리도 기능 편차.
- **기기 파편화**: 플래그십 한정. 저가/구형 폰은 아무것도 못 씀 → 클래식 ML Kit/템플릿이 바닥.
- **Wear OS**: 온디바이스 Nano 없음. 워치 "Gemini"는 클라우드. 구조상 Nano = 폰 전용.
- **언어 한계**: Summarization EN/JA/KO, Image Description 영어만, Rewriting 7개 언어. 한국어 대상이면 Summarization/Rewriting 한국어는 되나 **기기별 확인** 필요.
- **런타임 한계**: 포그라운드 전용 + 앱별 쿼터(`BUSY`/배터리) → 러닝 중 연속 호출 금지, **세션 경계에서 배치**.
- **무클라우드 확인**: 위 전부 AICore/Private Compute 온디바이스로 규칙 부합. Firebase Hybrid 고려 시 `ONLY_ON_DEVICE`만 허용.

## 권고 다음 단계 (랭킹)

1. **Rewriting API를 RuleCoach 문장의 톤/페르소나에 채택(spec + 소형 ADR).** 아키텍처 적합도 최고 — 내용은 규칙 확정, 톤만 변경(Friendly/Professional/Shorten)이라 "LLM은 표현만 + 가드레일" 모델을 강화하고 자유 프롬프트보다 결정론적. `CoachTextEngine` 포트 + 템플릿 폴백. **가장 잘 맞는 신규 기능.**
2. **Summarization을 세션 종료 리포트 내러티브에 채택(spec).** 도출 관측 → 1~3불릿 요약. "세밀한 관측 리포트" 제1원칙에 직결(없는 숫자 안 만듦 — 실제 도출값만 먹임). EN/JA/KO 게이트, 세션 종료 배치.
3. **현행 Prompt 사용을 Prefix Caching + 타이트한 GenerationConfig로 최적화(소형 ADR/엔지니어링 노트).** 저비용, 고정 프리앰블 지연/배터리 절감, `temperature`↓/`maxOutputTokens`↓로 간결/안정.
4. **구조화 출력 API 감시 ADR(결정: 보류).** JSON/스키마 출력이 파싱 대신 검증 객체를 주지만 온디바이스 "예정"(I/O 2026), beta 미포함 → 안정 출시 시 재검토. 그전까지 템플릿 파싱 + DirectionGuard 유지.

**지금은 아님**: Image Description(이미지 없음/영어만), Proofreading(교정 대상 없음), Speech Recognition(오디오 없음/alpha), 워치측 Nano(미지원).

## 출처(전체)

- ML Kit GenAI overview — https://developers.google.com/ml-kit/genai
- Summarization(Android) — https://developers.google.com/ml-kit/genai/summarization/android
- Rewriting(Android) — https://developers.google.com/ml-kit/genai/rewriting/android
- RewriterOptions.OutputType — https://developers.google.com/android/reference/com/google/mlkit/genai/rewriting/RewriterOptions.OutputType
- Image Description(Android) — https://developers.google.com/ml-kit/genai/image-description/android
- Prompt API(Android) / get-started — https://developers.google.com/ml-kit/genai/prompt/android/get-started
- Speech Recognition(Android) — https://developers.google.com/ml-kit/genai/speech-recognition/android
- Gemini Nano dev(AICore/Private Compute) — https://developer.android.com/ai/gemini-nano
- 블로그 2025-05 온디바이스 GenAI — https://android-developers.googleblog.com/2025/05/on-device-gen-ai-apis-ml-kit-gemini-nano.html
- 블로그 2025-08 Nano+ML Kit — https://android-developers.googleblog.com/2025/08/the-latest-gemini-nano-with-on-device-ml-kit-genai-apis.html
- 블로그 2025-10 Prompt API — https://android-developers.googleblog.com/2025/10/ml-kit-genai-prompt-api-alpha-release.html
- I/O 2026 라운드업 — https://developer.android.com/blog/posts/top-ai-on-android-updates-for-building-intelligent-experiences-from-google-i-o-26
- 블로그 2024-10 Nano 실험 접근 — https://android-developers.googleblog.com/2024/10/gemini-nano-experimental-access-available-on-android.html
- Chrome 구조화 출력(다른 플랫폼) — https://developer.chrome.com/docs/ai/structured-output-for-prompt-api
- InfoQ ML Kit GenAI/safety — https://www.infoq.com/news/2025/06/google-mlkit-genai-gemini-nano/
- Android Authority Nano 기능/기기 — https://www.androidauthority.com/gemini-nano-features-devices-3490062/
- Android Police 지원 폰 — https://www.androidpolice.com/what-phones-support-gemini-nano/
- Google Store Nano 멀티모달 — https://store.google.com/intl/en/ideas/articles/gemini-nano-offline/
- Gemini on Wear OS(클라우드) — https://blog.google/products/wear-os/gemini-wear-os-watches/
- ML Kit home(클래식) — https://developers.google.com/ml-kit
- ML Kit NLP 블로그 — https://android-developers.googleblog.com/2019/04/ml-kit-expands-into-nlp-with-language.html
- Text Recognition v2 — https://developers.google.com/ml-kit/vision/text-recognition/v2

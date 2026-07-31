# Zone2Runner

Android 폰 + Galaxy Watch 기반 **Zone 2 러닝 코칭 앱**. 심박을 보며 달리기를 실시간으로 코칭한다 — 판정과 사실은 규칙/통계가 정하고, 문장은 온디바이스 LLM(Gemini Nano)이 만들며, 모든 판단과 문장의 근거가 기록된다.

AI Specialist(설계) 인증 과제이며, 설계 문서가 핵심 산출물이다.

## 구조

```
app/      Android 폰 앱 (판정/개인화/분석/코칭/리포트)
wear/     Galaxy Watch 앱 (센서 수집 + 무로직 뷰어)
spec/     기능 명세 (인덱스: spec/README.md)
arch/     ADR + 아키텍처 문서 + DP 설계문서 (인덱스: arch/README.md)
framework/ 인증 강의 프레임워크 정본 (불변)
guide/    사용자용 앱 안내서 (측정/계산/표시 전 항목)
report/   인증 보고서 원고
```

## 빌드/실행

```bash
cd app  && ./gradlew :app:testDebugUnitTest :app:assembleDebug   # 폰 앱 테스트+빌드
cd wear && ./gradlew :app:assembleDebug                          # 워치 앱 빌드
./gradlew :app:installDebug                                      # 연결된 기기에 설치
```

시뮬레이션 모드가 있어 워치/실주행 없이 전체 파이프라인(가상 러너 폐루프)을 실행할 수 있다.

## 핵심 설계 원칙 (CLAUDE.md 상세)

- **예측 금지, 없는 숫자 금지** — 관측과 그 위의 도출/학습값만 쓴다.
- **판단은 규칙/통계, 언어는 LLM** — LLM은 판단하지 않고, 출력은 형식/방향/숫자 가드를 거친다.
- **모든 값과 문장에 근거** — 수치는 출처(도출/학습/설계) 구분, LLM 문장은 근거 관측+프롬프트+경로가 세션에 기록된다.

## 시작 문서

- 아키텍처 개요: `arch/architecture-overview.md`
- 진행 상태(작업 이어가기): `HANDOFF.md`
- 하네스 규칙: `CLAUDE.md`

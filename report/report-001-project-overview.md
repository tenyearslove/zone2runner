# Report-001: 과제 소개 및 개요

- **날짜**: 2026-06-25
- **용도**: 과제 제안 발표 슬라이드 원고 (슬라이드 1~2)

---

## 슬라이드 1 — 과제 소개

**과제 배경**
- Zone 2 유산소 운동은 개인마다 심박 구간이 달라 운동 중 현재 강도가 적절한지 스스로 판단하기 어렵다.
- 심박수 수치만으로는 Zone 2를 유지하는 데 한계가 있으며, 실시간 맥락을 반영한 행동 안내가 필요하다.

**과제 필요성**
- 개인별 HR-Pace 관계와 Cardiac Drift를 학습하는 AI 모델로 사용자에 맞는 Zone 2 구간을 점진적으로 정확하게 만들 수 있다.
- On-device LLM을 활용하면 오르막·날씨·페이스 변화 등 실시간 상황을 반영한 자연스러운 음성 코칭이 가능하다.

→ **과제 목표 및 가치 : Galaxy Watch 센서 데이터와 On-device LLM을 결합하여, 개인별 Zone 2 구간을 스스로 학습·보정하고 상황 맞춤 음성으로 코칭하는 Android 앱을 설계한다.**

---

## 슬라이드 2 — 과제 개요

**과제명**
Galaxy Watch 기반 개인화 Zone 2 러닝 코칭 앱 설계
- Samsung Health Sensor SDK와 On-device LLM을 활용하여 실시간 Zone 2 판단 및 상황 맞춤 음성 코칭을 제공하는 Android / Wear OS 앱 설계

---

**과제상세 (Work to do)**

목표 수준
- 실시간 심박 기반 Zone 2 판정·음성 코칭이 동작하고, 운동 누적에 따라 개인별 Zone 2가 자동 보정되는 Android 앱 구현

연구방안
- Samsung Health Sensor SDK + Wearable Data Layer로 Galaxy Watch 심박을 실시간 수집·전달
- 경량 온디바이스 AI로 Zone 2 진입·이탈 판정, On-device LLM이 상황 맥락 코칭을 생성·TTS 출력
- 누적 데이터 Bayesian 업데이트로 개인별 Zone 2 범위 점진 보정

측정 Metric
- Zone 2 판단 정확도 85% 이상 / 이탈 후 5초 이내 코칭 90% 이상 / 누적 운동 후 개인화 보정 동작 확인

---

**성과 및 기대효과**

기술 수준 향상도
- Samsung Health·Wearable 기반 실시간 헬스 데이터 수집 아키텍처와 온디바이스 AI+LLM 파이프라인 구현 역량 확보

사업 성과 기대효과
- 고가 장비 없이 개인 맞춤 Zone 2 코칭 제공 가능성 검증, Samsung Health 기반 AI 코칭 서비스 확장 기반 확보

---

## 관련 문서
- `spec/spec-001-requirements.md` — FR1~FR6, C01~C03
- `spec/spec-002-quality-attributes.md` — QA1~QA6

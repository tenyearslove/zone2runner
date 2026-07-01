# zone2runner — Claude Code Harness

> **작업 이어가기**: 진행 상황/설계 결정/다음 할 일은 `HANDOFF.md`에 있다. 새 환경에서 clone 후엔 `HANDOFF.md`를 먼저 읽고 이어간다.

## 프로젝트 개요
Android + Galaxy Watch 기반 Zone 2 운동 코칭 앱.
사용자의 심박수를 실시간으로 모니터링하고, Zone 2 유지 여부를 판단해 피드백을 제공한다.

## 핵심 규칙

### 내가 모르는 상태로 진행하지 않는다
- **모든 기능 설계 전**: `spec/spec-{nnn}-{title}.md` 에 명세 → 검토 → 승인 후 구현
- **아키텍처/기술 결정 전**: `arch/adr-{nnn}-{title}.md` 에 ADR 작성 → 검토 → 승인 후 적용
- **작업 단위**: 한 번에 하나의 작은 단위만 진행. PR은 단일 목적으로 유지

### 문서 규칙
| 종류 | 경로 | 용도 |
|------|------|------|
| Spec   | `spec/spec-{nnn}-{title}.md`     | 기능 명세 |
| ADR    | `arch/adr-{nnn}-{title}.md`      | 아키텍처 결정 기록 |
| Report | `report/report-{nnn}-{title}.md` | 발표·보고용 문서 |

- 번호는 3자리 (`001`, `002`, …)
- 제목은 구체적으로 (❌ "데이터 설계" → ✅ "심박수 로컬 캐시 전략")
- ADR은 반드시 2~3개 대안 비교 포함
- Report는 PPT/보고서 작성을 위한 텍스트 원고. 슬라이드 구조 그대로 작성
- `·` 기호 사용 금지. 열거는 `,` 또는 `/`, 강조는 `-` 사용

## 프로젝트 구조 (예정)
```
zone2runner/
├── app/              # Android 핸드폰 앱
├── wear/             # Galaxy Watch Wear OS 앱
├── shared/           # 공유 도메인 모델
├── spec/             # 기능 명세
├── arch/             # ADR 문서
├── report/           # 발표·보고용 문서
└── CLAUDE.md
```

## 기술 스택 (확정 전 ADR 필요)
- Android / Wear OS
- Samsung Health SDK (심박수 수집)
- 언어: Kotlin

## 개발 워크플로우
1. 기능/설계 논의 → Spec 또는 ADR 작성
2. 검토 승인
3. 소규모 단위 구현 (1 PR = 1 목적)
4. 리뷰 후 병합

## 자주 쓰는 스킬
- `/adr` — ADR 문서 작성
- `/spec` — 기능 명세 작성
- `/report` — 발표·보고용 문서 작성

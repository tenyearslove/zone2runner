# Report-017: Appendix — 인증 보고서 부록 구성

- **날짜**: 2026-08-01 (v1)
- **용도**: 인증 보고서 Appendix 파트의 조립 인덱스 + 페이지별 원고. 샘플 p18~31 구성을 따른다.
- **구성**: ① 지표 산출 근거 & 측정 기준(QA별) ② Use Case ③ Context View ④ DP별 상세 설계 내역 ⑤ 최종 Architecture 3뷰(Module/C&C/Deployment) ⑥ 품질속성 검증 결과(QA별 상세)

---

## Appendix 목차 페이지

| # | 페이지 | 원고/도식 정본 |
|---|---|---|
| A-1 | QA1~QA6 지표 산출 근거 & 측정 기준 (6페이지) | `report-015` Appendix 1절 |
| A-2 | Use Case | `arch/diagrams/05-usecase.png` + 본 문서 §2 |
| A-3 | Context View | `arch/diagrams/01-context.png` + 본 문서 §3 |
| A-4 | DP1~DP5 상세 설계 내역 (5페이지) | `report-008`~`report-012` 각 Appendix 슬라이드 |
| A-5 | 최종 Architecture (Module View) | `arch/diagrams/04-module-view.png` + 본 문서 §5 |
| A-6 | 최종 Architecture (C&C View) | `arch/diagrams/02-component-cnc.png`(상세) / `02b`(축약) + 본 문서 §6 |
| A-7 | 최종 Architecture (Deployment View) | `arch/diagrams/03-deployment.png` + 본 문서 §7 |
| A-8 | QA1~QA6 품질 속성 검증 결과 (6페이지) | `report-015` Appendix 2절 |

---

## §2. Appendix. Use Case

**(그림: `arch/diagrams/05-usecase.png`)**

- 액터는 둘이다 — **러너(사용자)**와 **개발자/검증자**. 검증자가 정식 액터인 것이 이 시스템의 특징(시뮬 모드가 제품 기능, 테스트가능성 DP5).
- 러너의 중심 흐름: 프로필 등록 → 세션 시작 → 실시간 존 확인/음성 코칭 → **말하기 테스트 응답**(코칭 흐름에 포함 — 답이 그 자리에서 판정 기준을 정정하는 HITL) → 리포트 열람 → **지표 설명/코칭 근거 열람**(리포트에서 확장 — 설명용이성의 사용자 접점).
- 부속 흐름: 코칭 방식 조정(빈도/음성/말투/더위/관절 — HOTL), AI 모델 준비(세션 시작 시 미준비면 안내로 확장), 이력/추세 확인.

## §3. Appendix. Context View

**(그림: `arch/diagrams/01-context.png`)**

- 시스템 경계: **Zone2Runner = 폰 앱(판정/분석/개인화/코칭의 단일 주체) + 워치 앱(측정/표시)**.
- 외부 4종: Health Services(워치 센서 API), 폰 GPS, **Gemini Nano/AICore**(온디바이스 LLM — 별도 프로세스), Open-Meteo(기온 참고, 세션당 1회) + OSM 지도 타일 서버(지도 표시용 타일 다운로드).
- LLM 왕복에 통제가 명시된다: 나가는 것은 "규칙이 확정한 사실+임무"뿐, 들어오는 문장은 "가드 3종 통과 시 채택, 아니면 단어 폴백". 모델 다운로드는 세션 밖에서만.
- 사용자에게 나가는 것에 "생성 근거(프로비넌스)"가 포함 — 설명용이성이 컨텍스트 수준에서 보인다.
- 점선 액터 = 시뮬 입력(개발/검증) — RunSource 교체 지점.

## §4. Appendix. DP별 상세 설계 내역

각 DP의 상세(문제점-QA 매핑, 채택안 구조표, QA 별점 종합, 평가의 조건, 예상 질문, 용어 대조, 근거 문헌)는 DP별 보고서 원고의 Appendix 슬라이드가 정본이다:

| DP | QA | 상세 페이지 정본 | 설계문서(전체) |
|---|---|---|---|
| DP1 | 설명용이성 | `report-008` Appendix | `arch/dp/dp-01-explainability/` |
| DP2 | 제어가능성 | `report-009` Appendix | `arch/dp/dp-02-controllability/` |
| DP3 | 기능적응성 | `report-010` Appendix | `arch/dp/dp-03-adaptability/` |
| DP4 | 강건성 | `report-011` Appendix | `arch/dp/dp-04-robustness/` |
| DP5 | 테스트가능성 | `report-012` Appendix | `arch/dp/dp-05-testability/` |

공통 형식: 문제점→걸리는 QA / 채택안의 구조 표(통제 5층, 적응 5단계, 방어 4층, 검증 4요소) / QA 별점 종합(6축) / **평가의 조건**(조건이 다르면 2안이 우선될 수 있음 명시) / 예상 질문 / 용어 대조(심사용) / 근거 문헌.

## §5. Appendix. 최종 Architecture (Module View)

**(그림: `arch/diagrams/04-module-view.png`)**

- 코드 정적 구조(패키지 의존): 폰 65파일 9패키지 + 워치 9파일 1패키지.
- **읽는 포인트 3가지**: ① `domain`이 리프(의존 0, 안드로이드 미의존) — 순수 모델. ② 결정 로직(`pipeline`/`coaching`/`analysis`)이 전부 안드로이드 미의존 → **단위 테스트 152개가 기기 없이 실행**(테스트가능성의 구조적 근거). ③ `sim`이 `sensor`의 RunSource 인터페이스를 구현(의존성 역전) — 검증 입력이 운영과 같은 경로로 흐른다.
- 워치는 뷰(Activity 2 + ZoneGaugeView)/서비스(측정/원격제어)/링크(경로/공유 상태) 3그룹 — 폰과는 코드 의존이 아니라 Wearable Data Layer(프로세스 간)로만 연결.

## §6. Appendix. 최종 Architecture (C&C View)

**(그림: `arch/diagrams/02-component-cnc.png` 상세 / `02b-component-cnc-simple.png` 축약)**

- 실행 시 컴포넌트와 커넥터: 센서 → 입력 가드레일 → 모델 서빙(규칙 판정+베이지안 경계+관측 분석+온디바이스 LLM) → 출력 가드레일(DirectionGuard/NumberGuard/SafetyGuard) → 설명/운영 서비스, 오른쪽에 Data Lake와 개인 적응 루프.
- 이 뷰의 축약본이 **DP1~DP5의 채택안(1안) 도식과 동일** — DP마다 새 그림을 만든 게 아니라 한 아키텍처를 다섯 QA 관점에서 본 것이다(DP 폴더에는 카운터 2안 도식만 별도 존재).
- 색 = 컴포넌트 성격(가드레일 주황/모델 서빙 파랑/설명 초록/운영 보라/저장 회청/적응 청록/외부 노랑), «...» = 강의 표준 컴포넌트 유형.

## §7. Appendix. 최종 Architecture (Deployment View)

**(그림: `arch/diagrams/03-deployment.png`)**

- 물리 배치 3노드 + 1클라우드: **워치**(Wear 앱 UI + RunService 포그라운드 측정 + Health Services), **폰**(앱 본체 + RunControlService 원격 제어 + 온디바이스 저장 5종), **AICore**(Gemini Nano — 별도 시스템 프로세스), Open-Meteo(HTTPS, 세션당 1회).
- 폰-워치 커넥터 = Wearable Data Layer 경로 목록(워치→폰 /hr /spm /talk, 폰→워치 /run/start, stop, mirror, mirrorhr, live, talk, talkdone — 7종 전체) — **/run/live(폰이 확정한 존의 1Hz 푸시)**가 "폰=단일 판정 주체" 결정의 배치상 증거.
- 저장 5종에 **세션+LLM 호출 프로비넌스(SessionStore)**가 명시 — 감사 기록이 어디 남는지 배치도에서 추적된다.
- AICore 별도 프로세스 주석: LLM 자체의 CPU/메모리는 앱에서 계측 불가 → 앱은 호출 지연/경로/앱 PSS만 기록(정직한 계측 한계 — 리포트 "LLM 사용" 카드의 ⓘ와 일치).

---

> 조립 순서(최종 보고서): 01 과제 소개 → 02 요구사항(FR/제약 = spec-001, QA = spec-002) → 03 설계(DP1~DP5 = report-008~012 본문 3장씩 + 최종 Architecture = report-013) → 04 구현 및 검증(구현 = report-014, 품질속성 검증 = report-015 본문 표) → 05 결론(report-016) → Appendix(본 문서 구성, 상세는 report-015 Appendix + report-008~012 Appendix).

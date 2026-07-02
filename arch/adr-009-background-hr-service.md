# ADR-009: 백그라운드/화면off HR 지속 수집 및 수신 (서비스화)

- **날짜**: 2026-07-02
- **상태**: Accepted (sensor-poc로 검증)
- **결정자**: 성시원
- **보고서 매핑**: 설계 - Architectural Decision (adr-008 후속, "화면off 러닝" 과제 해소)

---

## 맥락

실기기 테스트(sensor-poc)에서 문제가 드러났다.
- **워치**: 앱이 백그라운드로 가면 HR 전송이 끊기고, 앱을 다시 켜도 재전송이 안 됨.
- **폰**: Activity가 포그라운드일 때만 수신(리스너를 onResume/onPause에 붙임) → 화면 끄면 수신 중단.

러닝 앱은 본질적으로 "화면을 끄고 오래 달리는" 시나리오다. adr-008 line 58에서 예고한 과제를 여기서 결정한다.

근본 원인: adr-008이 채택한 **Health Services MeasureClient는 포그라운드 전용**이다. 앱이 백그라운드로 가면 측정이 스로틀/해제된다. 서비스로 감싸도 API 특성상 백그라운드 연속측정을 보장하지 못한다.

---

## 결정

### 1. 워치 HR 수집: MeasureClient → ExerciseClient + 포그라운드 서비스

| 기준 | A. MeasureClient (adr-008 채택) | **B. ExerciseClient + 포그라운드 서비스 (채택)** | C. Samsung Health Sensor SDK |
|------|------|------|------|
| 백그라운드/화면off | 불가 (포그라운드 전용) | **가능** (지속 알림 서비스) | 가능 (연속측정) |
| 러닝 지표 | HR spot | **HR/거리/페이스/속도/칼로리 네이티브** | HR/IBI/raw |
| 표준/승인 | 표준 | 표준 | Galaxy 전용, 승인 가능성 |
| 용도 적합 | 순간 측정 | **운동 세션 추적** | 정밀 생체(HRV) |

- **채택: B.** `ExerciseType.RUNNING` 세션을 포그라운드 서비스(`foregroundServiceType="health"`, 지속 알림) 안에서 `ExerciseClient`로 구동 → 화면 꺼도 HR 스트리밍 지속.
- 부수 이득: ExerciseClient가 거리/페이스/속도를 네이티브 제공 → 실제 wear 앱(spec-010)에서 GPS 손계산을 대체(정확도↑, 코드↓).
- HRV(IBI)가 향후 필요해지면 C로 승격(adr-008과 동일 기조).

### 2. 폰 HR 수신: Activity 리스너 → WearableListenerService

| 기준 | A. MessageClient 리스너(Activity) | **B. WearableListenerService (채택)** | C. 상시 포그라운드 서비스 |
|------|------|------|------|
| 백그라운드/종료 수신 | 불가(포그라운드만) | **가능** (메시지 도착 시 시스템이 앱을 깨움) | 가능 |
| 비용 | 낮음 | **낮음** (이벤트 구동) | 높음(상시 알림/자원) |
| 적합 | 포그라운드 표시 | **백그라운드 수신** | 상시 처리/집계 필요 시 |

- **채택: B.** manifest에 `/hr` path 필터를 건 `WearableListenerService` → 앱이 꺼져 있어도 수신 지속. 수신 사실은 알림으로 증빙.
- 화면 끈 채 **실시간 판정/코칭까지** 계속 돌려야 하면 그때 C(포그라운드 서비스)를 추가로 얹는다.

---

## 남는 제약 (중요)

**온디바이스 LLM(Gemini Nano)은 폰 포그라운드 전용**(adr-007, ErrorCode 30). 즉 이 결정으로 **HR 수집/전송/판정은 백그라운드 가능**해지지만, **LLM 음성 코칭 생성은 화면off에서 막힌다.** 화면off 러닝 중 코칭 대안(후속 결정 필요):
- (a) 코칭 순간만 짧게 포그라운드 승격, (b) 규칙 기반 TTS로 폴백(LLM 없이), (c) 화면 켜짐 유지 세션 모드.

---

## 결과 / 영향

- sensor-poc가 백그라운드 파이프라인(워치 서비스 송신 + 폰 서비스 수신)을 검증하도록 갱신됨. **실기기 재검증 대기**: 화면off/앱 백그라운드에서 HR이 폰 알림까지 계속 오는지.
- 실제 wear 앱(spec-010)은 ExerciseClient 기반 서비스로 전환(거리/페이스/속도 네이티브 채택). spec-010 "GPS 손계산"은 이 ADR로 대체 예정.
- 권한 추가: 워치 `FOREGROUND_SERVICE(_HEALTH)`, `ACTIVITY_RECOGNITION`, `BODY_SENSORS(_BACKGROUND)`, `POST_NOTIFICATIONS` / 폰 `POST_NOTIFICATIONS`.

## Sources
- [Health Services: MeasureClient vs ExerciseClient](https://developer.android.com/health-and-fitness/guides/health-services/measure-vs-monitor)
- [Track exercise with Health Services (foreground service)](https://developer.android.com/health-and-fitness/guides/health-services/active/exercise)
- [WearableListenerService](https://developer.android.com/training/wearables/data/events)
- [Foreground service types (health)](https://developer.android.com/about/versions/14/changes/fgs-types-required)

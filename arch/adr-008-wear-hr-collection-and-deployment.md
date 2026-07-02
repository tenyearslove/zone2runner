# ADR-008: Wear 실시간 HR 수집 API 및 Watch 앱 배포 방식

- **날짜**: 2026-07-02
- **상태**: Proposed (PoC로 검증 예정)
- **결정자**: 성시원
- **보고서 매핑**: 설계 - Architectural Decision (DP0/adr-001 후속)

---

## 맥락

adr-001 Hybrid(Watch=수집, Phone=AI)를 실제 구현하려면 세 가지를 정해야 한다.
1. 워치에서 실시간 HR을 어떤 API로 얻는가
2. Watch↔Phone 통신
3. Watch 앱을 어떻게 배포/설치하는가

그리고 PoC로 "심박이 실제로 오는지 + 위치/고도/경사/날씨를 얻는지"를 검증한다.

---

## 결정

### 1. 실시간 HR 수집 API

| 기준 | A. Wear OS Health Services | B. Samsung Health Sensor SDK |
|------|------|------|
| 접근성 | 표준(androidx.health.services), 승인 불필요 | Galaxy Watch4+, Samsung 설정/파트너 필요할 수 있음 |
| 데이터 | HR, 걸음, 페이스 등 | HR + IBI(HRV), PPG/ECG raw, 화면off 연속측정 |
| 이식성 | 모든 Wear OS 기기 | Galaxy 전용 |

- **채택: PoC는 A(Health Services)** — 빠르고 승인 불필요, 현 설계(HR-Pace 디커플링)에 충분.
- HRV(IBI)나 화면off 연속측정이 필요해지면 **B로 승격**. (spec-002 QA에서 HRV는 현재 미사용)

### 2. Watch↔Phone 통신

Wearable Data Layer(Play Services). 실시간 HR 스트림은 **MessageClient**(RPC, 저지연), 상태/이력 동기화는 DataClient. **채택: HR 실시간 = MessageClient.**

### 3. Watch 앱 배포 방식

- **개발/PoC**: 워치에 **직접 설치**(Android Studio 워치 타깃 또는 adb 무선 디버깅). 폰→워치 자동 사이드로드는 **없음**(디버그 빌드).
- **프로덕션**: **Play Store가 폰 앱 설치 시 페어링된 워치에 Wear 앱을 자동 설치**(같은 리스트로 게시 시) 또는 앱이 미설치를 감지해 설치를 유도. → **사용자가 수동으로 따로 설치할 필요는 대체로 없음**(Play가 처리, 폰 앱이 직접 푸시하는 건 아님).
- **페어링**: **기존 Galaxy Wearable 페어링 그대로 사용.** Data Layer는 그 위에서 동작. 개발 시 워치 ADB 디버깅 활성화 필요.

---

## PoC 범위 (검증 항목)

- **Watch 앱**: Health Services로 실시간 HR 수집 → MessageClient로 폰 전송. (가능하면 걸음/케이던스도)
- **Phone 앱**: HR 수신 표시 + FusedLocationProvider(위치/고도) + 고도 변화로 경사(slope) 계산 + (선택)날씨 API.
- **확인**: HR이 1~2초 주기로 실제 오는지 / 위치·고도·경사 얻는지 / 전송 지연(QA4 기여).
- **날씨/기온은 모델 입력이 아니다** — Zone2 판정 MLP(spec-006)와 개인화(spec-004)는 날씨를 안 쓴다(더위의 심박 영향은 디커플링 특징이 간접 반영). 날씨는 LLM 코칭 문장의 맥락(spec-005)일 뿐이라 **이번 PoC에서 제외**. 필요 시 추후 프롬프트에 기온만 추가.

---

## 결과 / 영향

- spec-003(HR 파이프라인)의 실체가 본 PoC로 검증된다. HrSource 추상화로 Mock/Health Services/Samsung SDK 교체 가능하게 유지(QA5).
- 화면off 시 HR 지속 수집은 Health Services ongoing exercise 또는 B로 별도 확인 필요(앞선 LLM 포그라운드 제약과 함께 "화면off 러닝" 시나리오 과제).

## Sources
- [Samsung Health Sensor SDK](https://developer.samsung.com/health/sensor/overview.html) / [Galaxy Watch→폰 HR 전송 예제](https://developer.samsung.com/health/blog/en/transfer-heart-rate-from-galaxy-watch-to-a-phone)
- [Wear OS Data Layer API](https://developer.android.com/training/wearables/data/overview)
- [Package & distribute Wear OS apps](https://developer.android.com/training/wearables/packaging) / [Standalone vs non-standalone](https://developer.android.com/training/wearables/apps/standalone-apps)

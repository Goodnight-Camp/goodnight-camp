# Goodnight Camp (굿나잇캠프)

> Android Automotive OS(AAOS) 기반 EV 차박 전용 앱  
> Pleos SDK를 활용해 차량 배터리 · 온도 · 위치 데이터를 실시간으로 읽어, 차박 중 에너지 관리와 아침 기상을 돕는다.

📄 **사용자 가이드**: [`docs/user-guide.pdf`](docs/user-guide.pdf)

---

## 프로젝트 개요

| 항목 | 내용 |
|---|---|
| 패키지명 | `com.example.pleostest` |
| minSdk | 28 (Android 9 / AAOS) |
| targetSdk | 34 |
| 언어 | Kotlin + Java (혼용) |
| 빌드 | Gradle Kotlin DSL |

### 사용 SDK / 라이브러리

| 라이브러리 | 버전 | 역할 |
|---|---|---|
| Pleos Vehicle | 2.3.2 | EV 배터리·효율·용량 조회 |
| Pleos FusedLocation | 1.1.3 | GPS 위치 수신 + 역지오코딩 |
| Pleos TextToSpeech | 3.3.1 | TTS (현재 미연동) |
| Pleos NaviHelper | 3.0.5 | 즐겨찾기(집) 조회, 경로 안내 |
| AndroidX Room | 2.6.1 | 과거 차박 기록 로컬 DB |
| Material Components | 1.12.0 | UI 컴포넌트 |

---

## 화면 흐름

```
SetUpActivity (차박 설정)
    │
    └─ [밤 계획 만들기] ──► PlanActivity (배터리 예산 미리보기)
                                │
                                ├─ [수정] ──────────────────► SetUpActivity (뒤로)
                                │
                                └─ [이대로 잘게요] ──────────► CampingActivity (캠핑 모드)
                                        │  (campingStartMin 기록 + SleepRecord DB 저장)
                                        │
                                        ├─ SLEEP_DELAY_MS 무동작 → 수면 오버레이 (절전)
                                        │       └─ [5분 더] → snooze 플래그 → 돌아옴
                                        │
                                        └─ 기상 시각 도달 (CarDataService 감지)
                                                │
                                                └─► WakeActivity
                                                        ├─ Phase A : 스누즈 / 기상 결정
                                                        │       └─ [5분 더] → CampingActivity (수면 오버레이 즉시)
                                                        └─ Phase B : 출발 준비 (배터리·시각·집 안내)
```

---

## 프로젝트 구조

```
automotive/src/main/
├── java/com/example/pleostest/
│   ├── GoodNightCampApp.kt          # Application — Room DB 싱글턴
│   │
│   ├── activity/
│   │   ├── BaseActivity.kt          # 공통 — 세로 경고 오버레이, 헤더 위치 갱신
│   │   ├── SetUpActivity.kt         # 차박 설정 화면 (기상 시각·온도·목적지·게스트 수)
│   │   ├── CampingActivity.kt       # 캠핑 모드 메인 화면
│   │   ├── WakeActivity.kt          # 기상 화면 (Phase A / Phase B)
│   │   └── PlanActivity.kt          # 배터리 예산 미리보기 + 차박 시작 진입점
│   │
│   ├── service/
│   │   └── CarDataService.kt        # Foreground Service — 60초 폴링, 기상 시각 감지
│   │
│   ├── db/
│   │   ├── SleepRecord.kt           # Room Entity — 과거 차박 기록
│   │   ├── SleepRecordDao.kt        # DAO — 최근 5건 조회 / 삭제
│   │   └── SleepDatabase.kt         # Room Database 싱글턴
│   │
│   ├── sdk/
│   │   ├── VehicleManager.kt        # Pleos Vehicle SDK 래퍼 — 배터리·온도·효율·용량
│   │   ├── LocationManager.kt       # Pleos FusedLocation 래퍼 — GPS (현재 SDK 지원 X -> 기존 android sdk 사용)
│   │   ├── NaviManager.kt           # Pleos NaviHelper 래퍼 — 즐겨찾기·목적지 조회, 귀가 경로 요청
│   │   ├── HomeRoute.kt             # HOME 즐겨찾기 → RouteInfo 변환 + 귀가 경로 결과 타입
│   │   ├── HomeTrip.kt              # 귀가 예측(requestTrip) 요청·응답 변환 + 결과 타입
│   │   └── OvernightHvac.kt         # 밤새 공조 소비 모델 + 지난 밤 실측 보정
│   │
│   ├── dialog/
│   │   ├── GcTimePickerDialog.kt    # 기상 시각 선택 다이얼로그
│   │   ├── DestinationPickerDialog.kt
│   │   └── DestinationSearchDialog.kt
│   │
│   ├── global/
│   │   ├── GlobalVariables.java     # 앱 전역 상태 (static 필드)
│   │   └── GlobalFunction.java      # 공통 유틸 함수 (배터리 계산 등)
│   │
│   ├── ui/
│   │   └── BatteryBudgetChartView.kt  # 배터리 예산 커스텀 차트 (claude가 만들어서 디테일하게는 모르곘음)
│   │
│   └── util/
│       └── GNCLog.java              # 로그 유틸
│
└── res/
    ├── layout/
    │   ├── activity_setup.xml
    │   ├── activity_camping.xml
    │   ├── activity_wake.xml        # Phase A + Phase B (FrameLayout 중첩)
    │   ├── activity_plan.xml
    │   ├── gc_view_header.xml       # 공통 헤더 (위치 · 날씨 · 시각)
    │   ├── gc_fragment_sleeping.xml # 수면 오버레이
    │   └── gc_item_stat.xml         # 배터리 stat 카드 아이템
    │
    ├── drawable/
    │   ├── gc_bg_app.xml            # 앱 기본 배경
    │   ├── gc_bg_card.xml           # 카드 배경
    │   ├── gc_bg_wake_time.xml      # 일출 그라디언트 (#1F1646 → #FA6B1A → #FFC738)
    │   ├── gc_bg_tint_active.xml    # 초록 틴트 (#E5F9EF)
    │   └── gc_bg_button_navigate.xml # 집으로 안내 버튼 (#313236)
    │
    └── values/
        ├── colors.xml               # 디자인 시스템 컬러 토큰
        ├── dimens.xml               # 간격·크기 토큰
        ├── styles.xml               # Widget.GC.* 스타일
        └── strings.xml
```

---

## 핵심 기능

### 1. 차박 설정 → 계획 확인 (`SetUpActivity` → `PlanActivity`)

**SetUpActivity**
- 기상 시각 (HH:mm) 선택
- 목적지 검색 및 거리 설정
- 실내 온도 목표값 설정 (+/- 1°C)
- 동승자 수 설정
- 귀가에 필요한 배터리 여유 % 계산
- 과거 차박 기록 최근 5건 조회 → 한 번에 적용
- 앱 시작 시 현재 위치 취득 (1회)

**PlanActivity** — `GlobalFunction.calculateBatteryBudget()` 결과를 시각화
- stat 4개: 현재 배터리 / 아침 예상 / 밤새 사용량 / 귀가 필요량
- `BatteryBudgetChartView` 커스텀 그래프로 배터리 예산 흐름 표시
- 화면 꺼지는 예상 시각 표시 (기상 시각 기준 7시간 전)

**귀가 필요량 — 순정 내비 예측 (`NaviManager.requestTripEstimate`)**
- 화면 진입 시 앱 추정식으로 먼저 표시 → `NaviHelper.requestTrip(TripRequest)` → `onTripInfo`가 오면 다시 그림 (경로 안내는 시작하지 않음)
- 대상: 고른 목적지 좌표, 없으면(0,0) 내비 HOME 즐겨찾기 — 이 경우 HOME을 귀가 목적지로 채택
- `집까지 필요 % = 현재 배터리 − TripInfo.estimatedSocAtDest`, 거리 = `TripInfo.distance` (실제 도로 거리)
  - 내비 화면의 "도착 시 배터리"와 같은 값 (실측: 서울역 KTX 23.8km, 100% → 96%, 필요 4% / 추정식은 47%)
- 표시: stat 라벨 "집까지 필요 · 내비", 그래프 "집까지 N%(내비 예측)"
- 내비 예측을 못 쓰면 기존 추정식(`목적지km ÷ 전비 ÷ 용량`) 유지: 10초 무응답, 집 미등록, 권한 없음, `onError`, 차량 배터리 미수신(기본값 80%와 섞지 않음)
- 필요 권한: `pleos.car.permission.NAVI_ROUTE` (`requestTrip`도 이 권한으로 검사)
- `TripInfo` 실측 단위: `distance` m · `eta` 초 · `energyConsumption` Wh
- 내비 서비스 연결(`initialize` 성공 콜백) 전 요청은 큐에 보관 후 연결 시 실행
- 주의: 내비는 `onTripInfo`를 연결된 모든 클라이언트에 보냄 → 대기 중 다른 앱이 `requestTrip`하면 그 결과를 받을 수 있음

**밤새 공조 소비량 — 기본 모델 × 내 차 실측 보정 (`sdk/OvernightHvac.kt`)**
- Pleos SDK에 공조 소비 전력·예측 API 없음 (Vehicle SDK 2.3.2 HVAC는 상태 조회·제어만, NaviHelper는 주행 에너지만) → 앱이 추정
- 기본 모델 (0.2 ~ 4.0kW 제한)
  - 난방: `0.3kW + 0.06kW/°C × (설정 − 외기) − 0.08kW × 추가 인원`
    - 공개 실측(테슬라 혹한 차박, 실내 20°C: −13°C 1.45 / −17°C 2.4 / −22°C 3.8 kWh·h) 범위에 맞춘 계수
  - 냉방: `0.3kW + 0.03kW/°C × (외기 − 설정) + 0.04kW × 추가 인원` — 실측 근거 없는 가정치 (기존 식은 냉방을 0으로 봤음)
- 실측 보정: [이대로 잘게요] 시 시작 배터리 %·예측 kW 기록 → [일어날게요] 시 종료 배터리 % 기록 (`sleep_records` v3)
  - 다음 계획부터 `실제 소비 ÷ (예측 kW × 실제 잔 시간)`의 중앙값으로 보정, 기록이 적으면 1.0 쪽으로: `1 + (중앙값 − 1) × n ÷ (n + 2)`, 0.5 ~ 2.5 제한
  - 제외: 2시간 미만(1% 해상도 = 0.77kWh), 배터리가 늘어난 밤(충전), 시작/종료 배터리 미수신
  - 표시: stat 라벨 "밤새 사용 · 내 기록 N밤 보정"
- 기상 화면 "밤새 사용"은 예측값 대신 실측(잠들 때 % − 지금 %), 모르면 예측값
- 주의: 앱은 차량 공조를 제어하지 않음 → 사용자가 차에서 같은 온도로 AUTO를 켜고 유틸리티 모드를 유지해야 모델 전제("설정 온도 유지")가 맞음
- AAOS VHAL `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE`(순간 충방전 전력)는 `android.car.permission.CAR_ENERGY`(dangerous)로 앱에서 읽기 가능함을 확인
  - 단, 에뮬레이터는 0W 고정이라 공조 연동 여부 미검증 · 차량 전체 소비량이며 예측값 아님 → 실차에서 추정식 보정용 후보
- [수정] → SetUpActivity 복귀
- [이대로 잘게요] → `campingStartMin` 기록 + `SleepRecord` DB 저장 → CampingActivity 진입

### 3. 캠핑 모드 (`CampingActivity`)
- 실시간 배터리 · 온도 · 기상까지 남은 시간 표시
- **절전 오버레이**: 5분 무동작 감지 시 수면 오버레이 표시
  - 오버레이 위 터치 → 타이머 리셋
  - [5분 더] → 스누즈 5분 연장 후 WakeActivity로 복귀
  - [캠핑 종료] → isCampingMode = false, 화면 종료
- `GlobalVariables.isCampingMode = true` → CarDataService 기상 알람 활성화
- 스누즈 복귀 시 `showSleepOverlayImmediately` 플래그로 오버레이 즉시 표시

> **테스트 주의**: `SLEEP_DELAY_MS`가 현재 **5초**로 설정되어 있음.  
> 실기기 배포 전 `5 * 60 * 1000L` (5분)으로 복구 필요.

### 4. 기상 시퀀스 (`CarDataService` → `WakeSequenceRunner` → `CarControl`)
잠들 때(`이대로 잘게요`) 다음에 오는 기상 시각을 `wakeAtMs`로 계산(자정 넘김 처리)하고, 기상 15분 전부터 차가 단계적으로 깨운다.

| 시각 | 동작 | 조건 |
|---|---|---|
| 15분 전 | 공조 켜기 · 설정 온도 · 앞유리 성에 제거 · 뒷유리 열선 (풍향은 유리+발, 차가 지원할 때) | 항상 (출발 안전 — 배터리 절약 모드여도 수행) |
| 15분 전 | 사이드미러 열선 | 바깥 ≤ 5°C |
| 10분 전 | 핸들 열선 · 시트 열선 (운전석 + 착좌 센서가 "앉음"인 앞좌석만, 중간 단계) | 바깥 ≤ 10°C |
| 10분 전 | 시트 통풍 | 바깥 ≥ 26°C |
| 5분 전 → 정각 | 앰비언트 조명 5% → 60% (제곱 곡선으로 처음엔 아주 천천히), 색은 일출처럼 주황 → 따뜻한 흰색 | 항상 |
| 5분 전 | 외기 유입 + 송풍 한 단계 ↑ (범위 절반까지) | 항상 |
| 5분 전 → 정각 | 앞 창문 살짝(이동 범위 10%) 열었다가 정각에 원래 위치로 | 5°C ≤ 바깥 ≤ 30°C, 이미 열려 있으면 건드리지 않음 |
| 정각 | 성에 제거 끄기 · 공조 오토 · 사이드미러 펴기 · 알림 + 음성 안내(Pleos TTS 온디바이스) + 기상 화면 | |

- **배터리 절약 모드**: 귀가 필요 + 여유를 빼고 남는 배터리가 5% 미만이면 열선류 생략
- **바깥 온도 미상**: 온도 조건이 붙은 동작(열선·통풍·창문)은 하지 않음
- **5분만 더**: 조명이 다시 은은하게 → 5분 동안 다시 밝아지며 정각에 재알림 (준비 동작은 반복 안 함)
- **일어날게요**: 남은 성에 제거·창문 정리, 미러 열선 끄기, 조명·내외기·송풍을 시퀀스 직전 상태로 복원 (시트·핸들 열선은 곧 운전하므로 유지)
- 차마다 지원 기능·범위가 달라 시작 시 capability를 먼저 조회하고 그 범위 안에서만 값을 씀 (예: 앞좌석 시트 온도 -3..3은 양수 쪽만 사용)
- 기상 화면 Phase A에 "깨우면서 차가 한 일" 카드 (✓ 완료 · — 이 차 미지원 · ✕ 권한 없음/실패), 수면 오버레이에 "기상 준비 중 · {단계}"

**사이드미러 펴기의 한계**: Pleos Vehicle SDK에는 미러 접기/펴기 API가 없다(사이드미러는 열선만). AAOS VHAL `MIRROR_FOLD`는 있지만
`android.car.permission.CONTROL_CAR_MIRRORS`(signature|privileged)가 필요해 일반 설치 앱은 받을 수 없다 → 항상 "권한 없음".
시스템 앱으로 탑재되면 `CarControl.unfoldMirrors`가 그대로 동작하도록 코드와 권한 선언은 남겨 둠.

**시연 (디버그 빌드만)**: 캠핑 화면에서 "기상까지"를 길게 누르면 지금 조건으로, "바깥"을 길게 누르면 영하 5°C 겨울 조건으로
기상 시퀀스를 15배속 실행 (15분 → 1분).

**에뮬레이터(Pleos Connect, Virtual Console) 실측**: 성에 제거(앞/뒤)·설정 온도·송풍·외기·공조 오토·앰비언트 밝기/색·창문·핸들 열선·시트 열선 모두
Virtual Console 상태에 반영됨. 풍향은 지원 목록이 비어 와서 건너뜀. 창문 위치는 0..10이고 0이 닫힘(에뮬레이터 기본값은 10=완전히 열림).

### 5. 기상 화면 (`WakeActivity`)
**Phase A — 기상 알람**
- 현재 배터리 / 귀가 필요 배터리 / 여유 배터리 3개 stat 카드
- 깨우면서 차가 한 일 카드 (기상 시퀀스 진행 결과)
- [5분 더] → 기상 시퀀스 스누즈 + CampingActivity 수면 오버레이 복귀
- [일어날게요] → 기상 시퀀스 정리(원래 상태 복원) + Phase B 전환

**Phase B — 출발 준비**
- 왼쪽: 밤새 배터리 사용량 요약 배너 · 현재/밤새 배터리 카드 · 피드백 (좋았어요/더웠어요/추웠어요)
- 오른쪽: 현재 시각 (일출 그라디언트 카드) · 집으로 안내 버튼 · 집 도착 예상 시각 및 잔량

**귀가 경로 실행 (`NaviManager.requestHomeRoute`)**
- [집으로 안내] → `getBookmarkInfo()` → `BookmarkType.HOME` 항목으로 `NaviHelper.requestRoute(RouteInfo)` 호출 → 순정 내비가 경로 안내 시작
- 결과(`HomeRouteResult`)는 메인 스레드에서 1회 전달, Toast로 안내
  - `Requested` : `onRouteStarted` 수신 또는 3초 내 오류 없음
  - `NoHomeBookmark` : 내비에 집 미등록 · `Timeout` : 즐겨찾기 5초 무응답 · `PermissionDenied` · `Failed(NaviErrorCode)`
- 요청 중 버튼 비활성화 (중복 탭 방지)
- 필요 권한: `pleos.car.permission.NAVI_ROUTE`(조회), `pleos.car.permission.NAVI_ROUTE_SEARCH`(경로 요청) — 누락 시 SDK가 `SecurityException`을 던짐
- Pleos Connect v2.1.0 에뮬레이터 실측 동작
  - 집 미등록이어도 내비는 `HOME` 항목을 좌표 (0,0)·빈 `poiId`로 보냄 → `HomeRoute`에서 미등록으로 판정
  - 부팅 후 순정 내비 화면을 한 번도 열지 않으면 `navi not ready`로 요청을 보류 → 앱은 5초 후 `Timeout`
  - `requestRoute` 후 내비가 경로 탐색 → "안내 시작" 카운트다운 → 약 7~8초 뒤 `onRouteStarted` (3초 대기 초과 시 `Requested`로 처리)

### 6. 차량 데이터 연동 (`VehicleManager`)
| 데이터 | SDK 메서드 |
|---|---|
| 배터리 잔량 (%) | `getEvBatteryStateOfCharge` |
| 외부 온도 (°C) | `CarPropertyManager` — `ENV_OUTSIDE_TEMPERATURE` |
| 충전 후 효율 (km/kWh) | `getSinceChargeEvEfficiency` |
| 배터리 용량 (Wh) | `getEvBatteryCapacity` |

---

### 7. 디자인 · 화면 (현대 레퍼런스 디자인 시스템)
디자인 기준은 조직 레포 [`Goodnight-Camp/.github`](https://github.com/Goodnight-Camp/.github)의 `design/core/DESIGN.md` (OmD, 레퍼런스 hyundai).
- **밤/아침 테마**: 앱 기본은 밤 팔레트(`values-night`, 깊은 네이비 + 현대 시안 주 버튼), 기상 화면만 아침 팔레트(`values`, 현대 네이비 주 버튼).
  `GoodNightCampApp`이 `MODE_NIGHT_YES`, `WakeActivity`가 `localNightMode = MODE_NIGHT_NO`
- 모서리 절반·그림자 제거·굵기 700/500, 본문 명암비 4.5:1 이상. 글꼴 Asta Sans (42dot, SIL OFL 1.1)
- **첫 화면**: 타일 구성 — 내일 아침 기상 시각(큰 시계) · 지금 차 상태(실내/바깥 온도) · 내일 갈 곳 · 도착할 때 남길 배터리(5~50% 슬라이더) · 함께 자는 인원 · [굿나잇 시작].
  온도는 사용자가 정하지 않고 **차에 설정된 공조 온도**(`getHvacTemperature`)를 계산에 씀. 실내 온도는 `getHvacTemperatureCurrent`
- **배터리 예산 곡선** (`OvernightProfile`, `WeatherForecast`): 캠핑 위치의 시간별 기온 예보(Open-Meteo, 위치는 소수 둘째 자리로 반올림)로
  15분마다 공조 전력을 다시 계산해 누적 → 새벽에 추워지면 뒤로 갈수록 가파른 곡선. 지금은 차 센서 값에 맞추고 3시간에 걸쳐 예보로 넘어감.
  예보를 못 받으면(5초 타임아웃) 지금 기온 고정으로 계산하고 화면에 표시
- **캠핑 화면**: "아침까지 차가 할 일" 타임라인(기상 시퀀스 시간표, 진행되면 체크) + 아침 예상 잔량 · 내일 갈 곳
- **수면 화면**: 코드로 그린 은은한 모닥불(`CampfireView`, 밝기 55%, 보일 때만 ~20fps, 애니메이션 끔 설정 시 정지). 기상 시퀀스가 시작되면 불이 커짐
- **기상 화면**: 알람(A) — 일출 카드 + 배터리 판단 + 차가 한 일 / 출발 준비(B) — 어젯밤 기록(잠든→일어난 시각, 잔 시간) · 집으로 안내
- **차박 종료**: 앱을 끄지 않고 종료 화면(출발 준비)으로. 진행 중인 기상 시퀀스는 중단·원상 복원, 뒤로 가면 첫 화면

---

## 미구현 / 미연동 기능

| 기능 | 위치 | 비고                                                                           |
|---|---|------------------------------------------------------------------------------|
| 일출 시각 계산 | `GlobalVariables.sunriseTime` | 필드만 선언, 실제 위치 기반 계산 미구현                                                      |
| 설정값 영속화 | `SetUpActivity` | `wakeTime` · `setTemperatureDeg` 등 앱 재시작 시 초기값으로 리셋됨 (SharedPreferences 미연동) |
| 실내 온도 제어 | `CampingActivity` | 목표 온도는 설정 가능하나 실제 HVAC 제어 미구현                                                |
| 피드백 저장 | `WakeActivity` Phase B | 선택 UI만 있고 DB 저장 없음                                                           |

---

## ADB 테스트 검증 목록

### 환경 준비

```bash
# 디바이스 연결 확인
adb devices

# 앱 설치
adb install -r app-debug.apk

# 로그 필터 (GNC 태그)
adb logcat -s GNC
```

---

### ✅ 기능별 검증 시나리오

#### 1. 앱 기동 및 차량 데이터 수신

```bash
# CarDataService 기동 확인
adb logcat -s GNC | grep "CarDataService"

# 배터리 · 온도 조회 확인
adb logcat -s GNC | grep -E "배터리|온도|VehicleManager"
```

- [ ] 앱 시작 후 헤더에 위치명이 "로딩 중.." → 실제 위치명으로 바뀌는지 확인
- [ ] 배터리 % 가 "-" → 실수치로 갱신되는지 확인
- [ ] 에뮬레이터 환경에서 SDK 미연결 시 오류 로그만 출력되고 크래시 없는지 확인

---

#### 2. 절전 오버레이 

```bash
adb logcat -s GNC | grep -E "절전|sleepOverlay|SLEEP"
```

- [ ] CampingActivity 진입 후 세팅된(GlobalVarible.SLEEP_DELAY_MS) 초 무동작 → 수면 오버레이 출현
- [ ] 오버레이 터치 → 오버레이 사라지고 타이머 리셋
- [ ] 오버레이에서 [5분 더] → `snoozeUntilMs` 설정 로그 확인 → CampingActivity 복귀 → 오버레이 즉시 재출현
- [ ] 오버레이에서 [캠핑 종료] → 액티비티 종료 확인

> **배포 전 필수**: `SLEEP_DELAY_MS = 5 * 1000L` → `5 * 60 * 1000L` 로 변경

---

#### 3. 기상 알람 (CarDataService)

```bash
# wakeTime을 현재시각 +1분으로 강제 설정 후 확인
```

- [ ] `isCampingMode = true` 상태에서 기상 시각 도달 → WakeActivity 자동 시작
- [ ] WakeActivity 시작 후 `wakeAlarmTriggered = true` 로그 확인
- [ ] [5분 더] 탭 → `snoozeUntilMs` = 현재 + 5분, `wakeAlarmTriggered = false` 로그 확인
- [ ] 스누즈 후 5분 경과 → WakeActivity 재시작 확인

---

#### 4. 기상 화면 Phase A → Phase B 전환

```bash
# WakeActivity는 exported=false → 일반 adb shell로는 Permission Denial.
# Google APIs 이미지는 adb root 후 실행 가능, Pleos Connect 이미지(user 빌드)는 root 불가
#   → 실제 흐름(밤 계획 → 이대로 잘게요 → 기상 시각 도달)으로 진입
adb root
adb shell am start -n com.example.pleostest/.activity.WakeActivity
adb logcat -s GNC | grep "WakeActivity"
```

- [ ] Phase A 진입 — stat 3개(현재·필요·여유 배터리) 올바르게 표시
- [ ] [일어날게요] → Phase B 전환 애니메이션 없이 즉시 전환 확인
- [ ] Phase B — 현재 시각이 실시간 반영되는지 확인
- [ ] Phase B — 집으로 안내 버튼 탭 → `requestRoute → <집 이름>` 로그 + 순정 내비 경로 안내 시작 + "경로 안내를 시작해요" Toast
- [ ] 내비에 집 미등록 상태에서 탭 → "집이 등록되어 있지 않아요" Toast, 경로 요청 로그 없음
- [ ] 내비 서비스 없는 에뮬레이터에서 탭 → 5초 후 "응답하지 않아요" Toast, 크래시 없음
- [ ] 응답 오기 전 연타 → `이미 진행 중 → 무시` 로그, 요청 1회만 발생
- [ ] Phase B — 피드백 버튼 선택 → 선택된 버튼 초록 하이라이트, 나머지 기본 배경으로 복귀

---

#### 5. 밤 계획 화면 (`PlanActivity`)

```bash
adb root   # PlanActivity도 exported=false
adb shell am start -n com.example.pleostest/.activity.PlanActivity
adb logcat -s GNC | grep "PlanActivity"
```

- [ ] SetUpActivity에서 [밤 계획 만들기] → PlanActivity 진입 확인
- [ ] stat 4개 (현재·아침·밤새 사용·귀가 필요) 올바르게 표시되는지 확인
- [ ] 내비에 집 등록 상태에서 진입 → `[NaviManager] 귀가 예측 결과: Estimated(...)` 로그 + "집까지 필요 · 내비" 표시 확인
- [ ] 집 미등록 상태 → `NoHomeBookmark` 로그 + 추정식 값 유지 확인
- [ ] 밤 계획 후 [수정]으로 복귀 → 설정 화면 목적지가 내비 HOME 이름·도로 거리로 갱신됐는지 확인
- [ ] `BatteryBudgetChartView` 그래프 렌더링 오류 없는지 확인
- [ ] 화면 꺼지는 예상 시각 = `wakeTime` 기준 7시간 전으로 계산 맞는지 확인
- [ ] [수정] → SetUpActivity 복귀 확인
- [ ] [이대로 잘게요] → `sleep_records` DB 1건 삽입 + CampingActivity 진입 확인

---

#### 6. Room DB — 과거 기록

```bash
# DB 직접 열기 (루팅 기기 또는 에뮬레이터)
adb shell run-as com.example.pleostest
sqlite3 /data/data/com.example.pleostest/databases/sleep_records.db \
    "SELECT * FROM sleep_records ORDER BY createdAt DESC LIMIT 5;"
```

- [ ] SetUpActivity에서 차박 시작 시 `sleep_records`에 레코드 1건 삽입 확인
- [ ] 과거 기록 목록에 최근 5건만 표시
- [ ] 기록 탭 → 설정값(기상시각·온도·게스트 수 등) 자동 적용 확인
- [ ] 기록 삭제 → 목록에서 즉시 제거 확인

---

#### 7. 세로 모드 경고 오버레이

```bash
# 세로 회전 시뮬레이션 (에뮬레이터)
adb shell settings put system accelerometer_rotation 1
adb shell am broadcast -a android.intent.action.CONFIGURATION_CHANGED
```

- [ ] 세로 모드 전환 → 검정 반투명 오버레이 출현 (`#FF131316`)
- [ ] 가로 모드 복귀 → 오버레이 사라짐

---

#### 8. 전체 플로우 E2E

```bash
adb logcat -s GNC -v time > gnc_e2e.log &
```

1. 앱 시작 → SetUpActivity
2. 기상 시각을 현재 +2분으로 설정
3. [밤 계획 만들기] → PlanActivity — stat 4개·그래프 확인
4. [이대로 잘게요] → CampingActivity 진입 + DB 저장 로그 확인
5. `SLEEP_DELAY_MS` 무동작 → 수면 오버레이 확인
6. 오버레이 터치 → 해제 확인
7. 2분 대기 → WakeActivity 자동 진입 확인
8. [일어날게요] → Phase B 확인
9. `gnc_e2e.log` 에서 에러 로그 없는지 검토

---

## 개발 메모

- `GlobalVariables`는 static 필드 기반 — 프로세스 종료 시 초기화됨. 앱 재시작 후 설정값이 날아가는 현상은 의도된 임시 구조.


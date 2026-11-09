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
│   │   └── NaviManager.kt           # Pleos NaviHelper 래퍼 — (구현 필요)
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

### 4. 기상 감지 (`CarDataService`)
- 앱 실행 시 Foreground Service로 시작, 60초 간격 폴링
- 조건: `isCampingMode == true` && `wakeAlarmTriggered == false` && `현재시각 >= snoozeUntilMs` && `현재시각(분) >= wakeTime(분)`
- 조건 충족 시 `WakeActivity` 시작 (`FLAG_ACTIVITY_NEW_TASK`)
- 스누즈 시 `wakeAlarmTriggered = false`로 리셋 → 5분 후 재감지

### 5. 기상 화면 (`WakeActivity`)
**Phase A — 기상 알람**
- 현재 배터리 / 귀가 필요 배터리 / 여유 배터리 3개 stat 카드
- [5분 더] → 스누즈 + CampingActivity 수면 오버레이 복귀
- [일어날게요] → Phase B 전환

**Phase B — 출발 준비**
- 왼쪽: 밤새 배터리 사용량 요약 배너 · 현재/밤새 배터리 카드 · 피드백 (좋았어요/더웠어요/추웠어요)
- 오른쪽: 현재 시각 (일출 그라디언트 카드) · 집으로 안내 버튼 · 집 도착 예상 시각 및 잔량

### 6. 차량 데이터 연동 (`VehicleManager`)
| 데이터 | SDK 메서드 |
|---|---|
| 배터리 잔량 (%) | `getEvBatteryStateOfCharge` |
| 외부 온도 (°C) | `CarPropertyManager` — `ENV_OUTSIDE_TEMPERATURE` |
| 충전 후 효율 (km/kWh) | `getSinceChargeEvEfficiency` |
| 배터리 용량 (Wh) | `getEvBatteryCapacity` |

---

## 미구현 / 미연동 기능

| 기능 | 위치 | 비고                                                                           |
|---|---|------------------------------------------------------------------------------|
| 기상 알림 | `WakeActivity.onCreate()` | TTS로 할지 뭐로할지 모르겟음                                                            |                                                                         |                                                                           |                                                                            |
| 집으로 경로 안내 실행 | `WakeActivity` Phase B 버튼 | `naviManager.requestPlaceList` 까지는 구현, `requestRoute(lat, lng)` SDK 메서드 미확인  |
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
adb shell am start -n com.example.pleostest/.activity.WakeActivity
adb logcat -s GNC | grep "WakeActivity"
```

- [ ] Phase A 진입 — stat 3개(현재·필요·여유 배터리) 올바르게 표시
- [ ] [일어날게요] → Phase B 전환 애니메이션 없이 즉시 전환 확인
- [ ] Phase B — 현재 시각이 실시간 반영되는지 확인
- [ ] Phase B — 집으로 안내 버튼 탭 → 로그에 즐겨찾기 조회 결과 출력 확인
- [ ] Phase B — 피드백 버튼 선택 → 선택된 버튼 초록 하이라이트, 나머지 기본 배경으로 복귀

---

#### 5. 밤 계획 화면 (`PlanActivity`)

```bash
adb shell am start -n com.example.pleostest/.activity.PlanActivity
adb logcat -s GNC | grep "PlanActivity"
```

- [ ] SetUpActivity에서 [밤 계획 만들기] → PlanActivity 진입 확인
- [ ] stat 4개 (현재·아침·밤새 사용·귀가 필요) 올바르게 표시되는지 확인
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


package com.example.pleostest.global;

public class GlobalVariables {

    public static final Long POLL_INTERVAL_MS = 1 * 60_000L; //초 * 1000
    // 차량 상태 (SDK에서 갱신)
    public static String curBattery = "-";
    public static String outTemperature = "-";
    // 실내 온도 (Pleos HVAC getHvacTemperatureCurrent) — "-" = 미수신
    public static String cabinTemperature = "-";
    public static String locationName = "로딩 중..";
    public static double currentLat = 0.0;
    public static double currentLng = 0.0;
    public static boolean utilityModeOn = true;

    // 사용자 설정값 — 화면에서 변경 가능, 나중에 SQLite 연동 예정
    public static String wakeTime = "06:30";
    public static int setTemperatureDeg = 20;
    public static int marginBatteryPct = 10;
    public static int guestCount = 1;

    // 목적지
    public static String destinationName = "집 · 마포구";
    public static int destinationDistanceKm = 182;
    // 목적지 좌표 — (0,0)이면 미상 → 내비 HOME 즐겨찾기로 귀가 예측
    public static double destinationLat = 0.0;
    public static double destinationLng = 0.0;
    public static int neededForHomePct = 35;   // 집까지 필요한 배터리 %

    // 일출 시간 (위치 기반으로 갱신)
    public static String sunriseTime = "-";

    // EV 차량 스펙 (SDK에서 갱신, 기본값은 Ioniq 5 77.4kWh 기준)
    public static float batteryCapacityWh = 77400f;
    public static float efficiencyKmPerKwh = 5.0f;

    // 밤 계획 결과 (밤 계획 만들기 이후 채워짐)
    public static int morningBatteryPct = 0;
    public static int overnightUsePct = 0;
    // 잠들 때 실제 배터리 % (-1 = 미상) — 기상 화면에서 실측 밤새 사용량 계산
    public static int sleepStartSocPct = -1;

    // 캠핑 모드 시작 시각 (분 단위, -1 = 미시작)
    public static int campingStartMin = -1;
    // 이번 밤의 기상 시각 (epoch ms, 0 = 미정) — 잠들 때 다음에 오는 wakeTime으로 계산 (자정 넘김 처리)
    public static long wakeAtMs = 0L;

    //절전 오버레이 돌입을 위한 인터렉션 무응답 대기 시간
    public static long SLEEP_DELAY_MS = 5 * 1000L;
    // 5분만 더 스누즈 — 이 시각(ms) 이전엔 기상 화면 안 띄움
    public static long snoozeUntilMs = 0L;

    // 스누즈 복귀 시 수면 오버레이 즉시 표시 플래그
    public static boolean showSleepOverlayImmediately = false;

    // 캠핑 모드 활성 여부 (Service의 wake 체크 조건)
    public static boolean isCampingMode = false;

    // 기상 알람 발동 여부 — 스누즈 시 false로 리셋해 재발동 허용
    public static boolean wakeAlarmTriggered = false;

    // 이전 코드 호환용 상수 (initWakeTime 등)
    public static final String initWakeTime = "06:30";
    public static final String initSetTemperature = "20°C";
    public static final String initMarginBattery = "10%";
}

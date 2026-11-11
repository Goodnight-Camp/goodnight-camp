package com.example.pleostest.global;

import android.view.View;
import android.widget.TextView;

import com.example.pleostest.R;
import com.example.pleostest.sdk.HourlyTemp;
import com.example.pleostest.sdk.HvacCalibration;
import com.example.pleostest.sdk.OvernightHvac;
import com.example.pleostest.sdk.OvernightProfile;
import com.example.pleostest.sdk.OvernightProfiles;
import com.example.pleostest.util.GNCLog;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;


public class GlobalFunction {

    // ────────────────────────────────────────────────────────────────
    // 배터리 예산 데이터 클래스
    // ────────────────────────────────────────────────────────────────

    public static class BatteryBudgetResult {
        public final int currentPct;
        public final int morningPct;
        public final int overnightUsePct;
        public final int neededForHomePct;
        public final int reservePct;
        public final boolean isViable;
        public final int startTimeMin;
        public final int wakeTimeMin;
        /** true = neededForHomePct가 순정 내비 예측값, false = 앱 자체 추정식 */
        public final boolean neededFromNavi;
        /** 보정 전 기본 모델의 평균 공조 소비 전력(kW) — 수면 기록에 저장해 다음 보정에 사용 */
        public final double baseHvacKw;
        /** 공조 소비 보정에 쓴 지난 밤 수 (0 = 보정 없음) */
        public final int calibrationNights;
        /** 밤사이 배터리 곡선 — x: 잠드는 시각 0 → 기상 1, y: 예상 % */
        public final double[] curveX;
        public final double[] curveY;
        /** true = 시간별 기온 예보 반영, false = 지금 기온이 밤새 그대로라고 가정 */
        public final boolean forecastUsed;
        /** 계산에 쓴 밤사이 최저 기온 */
        public final float minTempC;

        public BatteryBudgetResult(int currentPct, int morningPct, int overnightUsePct,
                                   int neededForHomePct, int reservePct, boolean isViable,
                                   int startTimeMin, int wakeTimeMin, boolean neededFromNavi,
                                   double baseHvacKw, int calibrationNights,
                                   double[] curveX, double[] curveY, boolean forecastUsed, float minTempC) {
            this.curveX = curveX;
            this.curveY = curveY;
            this.forecastUsed = forecastUsed;
            this.minTempC = minTempC;
            this.neededFromNavi = neededFromNavi;
            this.baseHvacKw = baseHvacKw;
            this.calibrationNights = calibrationNights;
            this.currentPct = currentPct;
            this.morningPct = morningPct;
            this.overnightUsePct = overnightUsePct;
            this.neededForHomePct = neededForHomePct;
            this.reservePct = reservePct;
            this.isViable = isViable;
            this.startTimeMin = startTimeMin;
            this.wakeTimeMin = wakeTimeMin;
        }

        public int getCurrentPct() { return currentPct; }
        public int getMorningPct() { return morningPct; }
        public int getOvernightUsePct() { return overnightUsePct; }
        public int getNeededForHomePct() { return neededForHomePct; }
        public int getReservePct() { return reservePct; }
        public boolean getIsViable() { return isViable; }
        public int getStartTimeMin() { return startTimeMin; }
        public int getWakeTimeMin() { return wakeTimeMin; }
    }

    // ────────────────────────────────────────────────────────────────
    // UI 헬퍼
    // ────────────────────────────────────────────────────────────────

    /** gc_item_stat.xml을 include한 뷰에 값·단위·라벨을 한 번에 세팅 */
    public static void setStatItem(View statView, String value, String unit, String label) {
        ((TextView) statView.findViewById(R.id.gc_stat_value)).setText(value);
        ((TextView) statView.findViewById(R.id.gc_stat_unit)).setText(unit);
        ((TextView) statView.findViewById(R.id.gc_stat_label)).setText(label);
    }

    // ────────────────────────────────────────────────────────────────
    // 시간 변환
    // ────────────────────────────────────────────────────────────────

    /** "HH:mm" → 분 */
    public static int hhmmToMinutes(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    /** 분 → "HH:mm" */
    public static String minutesToHHmm(int totalMinutes) {
        return String.format(Locale.getDefault(), "%02d:%02d",
                (totalMinutes / 60) % 24, totalMinutes % 60);
    }

    /** 지금부터 GlobalVariables.wakeTime까지 남은 시간(단위: 시) */
    public static double overnightHours() {
        int wakeMin = hhmmToMinutes(GlobalVariables.wakeTime);
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int diff = wakeMin > nowMin ? wakeMin - nowMin : (24 * 60 - nowMin + wakeMin);
        return diff / 60.0;
    }

    // ────────────────────────────────────────────────────────────────
    // GlobalVariables 파싱
    // ────────────────────────────────────────────────────────────────

    /** curBattery 문자열에서 숫자 부분만 추출 (예: "85%" → "85", "-" → "-") */
    public static String batteryValueStr() {
        StringBuilder sb = new StringBuilder();
        for (char c : GlobalVariables.curBattery.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
        }
        return sb.length() > 0 ? sb.toString() : "-";
    }

    /** 차량에서 실제로 받은 배터리 %. 아직 못 받았으면 null (기본값 80을 쓰지 않음) */
    public static Integer batteryPctOrNull() {
        String digits = batteryValueStr();
        try {
            return digits.equals("-") ? null : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** curBattery 문자열에서 정수 추출 — 파싱 실패 시 기본값 80 반환 */
    public static int batteryPct() {
        String digits = batteryValueStr();
        try {
            return digits.equals("-") ? 80 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 80;
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 배터리 예산 계산
    // ────────────────────────────────────────────────────────────────

    /**
     * GlobalVariables에서 직접 읽어 밤새 배터리 소비 예산을 계산합니다.
     *
     * 공조 소비 모델: {@link OvernightHvac} (난방·냉방 기본 모델 × 내 차 실측 보정 계수)
     * 귀가 필요 에너지:
     *   neededKwh = destinationKm / efficiencyKmPerKwh
     */
    public static BatteryBudgetResult calculateBatteryBudget() {
        return calculateBatteryBudget(-1, HvacCalibration.NONE);
    }

    /**
     * @param naviNeededForHomePct 순정 내비 requestTrip 예측으로 구한 "집까지 필요 %".
     *                             0 이상이면 자체 추정식 대신 이 값을 쓴다. -1 = 없음(추정식 사용).
     * @param calibration          지난 밤 실측으로 구한 공조 소비 보정 계수
     */
    public static BatteryBudgetResult calculateBatteryBudget(int naviNeededForHomePct, HvacCalibration calibration) {
        return calculateBatteryBudget(naviNeededForHomePct, calibration, null);
    }

    /**
     * @param forecast 캠핑 위치의 시간별 기온 예보 (null = 없음 → 지금 기온이 밤새 그대로라고 가정)
     */
    public static BatteryBudgetResult calculateBatteryBudget(int naviNeededForHomePct, HvacCalibration calibration,
                                                             List<HourlyTemp> forecast) {
        int currentPct = batteryPct();
        double capacityKwh = Math.max(GlobalVariables.batteryCapacityWh / 1000.0, 1.0);
        double overnightHrs = overnightHours();
        float outsideCelsius = temperatureCelsius();

        GNCLog.i("[GlobalFunction] 배터리 예산 계산 시작 — battery=" + currentPct + "%, capacity=" + capacityKwh + "kWh, outside=" + outsideCelsius + "°C, setTemp=" + GlobalVariables.setTemperatureDeg + "°C, guests=" + GlobalVariables.guestCount + ", dest=" + GlobalVariables.destinationDistanceKm + "km, efficiency=" + GlobalVariables.efficiencyKmPerKwh + "km/kWh, overnightHrs=" + overnightHrs);

        // 공조 소비 — 15분마다 그 시각 기온(예보, 없으면 지금 기온)으로 기본 모델 × 실측 보정을 누적
        long nowMs = System.currentTimeMillis();
        long endMs = nowMs + (long) (overnightHrs * 3_600_000L);
        OvernightProfile profile = OvernightProfiles.INSTANCE.build(nowMs, endMs, currentPct, capacityKwh,
                GlobalVariables.setTemperatureDeg, GlobalVariables.guestCount, calibration.getFactor(),
                outsideCelsius, forecast);
        double baseKw = profile.getAvgBaseKw();
        GNCLog.d("[GlobalFunction] HVAC 소비 — " + (profile.getForecastUsed() ? "시간별 예보 반영" : "지금 기온 고정")
                + ", 평균 baseKw=" + String.format(Locale.US, "%.3f", baseKw) + "kW × 보정 "
                + String.format(Locale.US, "%.2f", calibration.getFactor()) + "(" + calibration.getNights() + "밤)"
                + ", 최저 " + String.format(Locale.US, "%.1f", profile.getMinTempC()) + "°C");

        // 밤새 소비 %
        double overnightKwh = profile.getUsedKwh();
        int overnightPct = (int) Math.min(Math.max(Math.round((overnightKwh / capacityKwh) * 100), 0), 100);
        int morningPct = Math.max(currentPct - overnightPct, 0);
        GNCLog.d("[GlobalFunction] 밤새 소비 — overnightKwh=" + String.format(Locale.US, "%.2f", overnightKwh) + "kWh, overnightPct=" + overnightPct + "%, morningPct=" + morningPct + "%");

        // 귀가 필요 % — 내비 예측이 있으면 우선, 없으면 직선거리 기반 추정식
        boolean fromNavi = naviNeededForHomePct >= 0;
        int neededForHomePct;
        if (fromNavi) {
            neededForHomePct = Math.min(naviNeededForHomePct, 100);
            GNCLog.d("[GlobalFunction] 귀가 필요 — 내비 예측 사용: " + neededForHomePct + "%");
        } else {
            double eff = Math.max(GlobalVariables.efficiencyKmPerKwh, 1.0);
            double neededKwh = GlobalVariables.destinationDistanceKm / eff;
            neededForHomePct = (int) Math.min(Math.max((neededKwh / capacityKwh) * 100, 0), 100);
            GNCLog.d("[GlobalFunction] 귀가 필요 — 추정식 사용: neededKwh=" + String.format(Locale.US, "%.2f", neededKwh) + "kWh → " + neededForHomePct + "%");
        }
        int reservePct = Math.min(neededForHomePct + GlobalVariables.marginBatteryPct, 100);
        GNCLog.d("[GlobalFunction] 귀가 필요 — neededForHomePct=" + neededForHomePct + "%, margin=" + GlobalVariables.marginBatteryPct + "%, reservePct=" + reservePct + "%");

        Calendar cal = Calendar.getInstance();
        int startMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int wakeMin = hhmmToMinutes(GlobalVariables.wakeTime);
        boolean viable = morningPct >= reservePct;
        GNCLog.i("[GlobalFunction] 예산 계산 완료 — morningPct=" + morningPct + "%, reservePct=" + reservePct + "%, viable=" + viable);

        return new BatteryBudgetResult(
                currentPct,
                morningPct,
                overnightPct,
                neededForHomePct,
                reservePct,
                viable,
                startMin,
                wakeMin,
                fromNavi,
                baseKw,
                calibration.getNights(),
                profile.getXs(),
                profile.getPcts(),
                profile.getForecastUsed(),
                profile.getMinTempC()
        );
    }

    /** outTemperature 문자열에서 float 값 반환 — 파싱 실패 시 기본값 15.0 반환 */
    public static float temperatureCelsius() {
        try {
            return Float.parseFloat(temperatureValueStr());
        } catch (NumberFormatException e) {
            return 15.0f;
        }
    }

    /** outTemperature 문자열에서 숫자(+마이너스 부호) 추출 (예: "-3°C" → "-3") */
    public static String temperatureValueStr() {
        StringBuilder sb = new StringBuilder();
        boolean hasDigit = false;
        for (char c : GlobalVariables.outTemperature.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
                hasDigit = true;
            } else if (c == '-' && sb.length() == 0) {
                sb.append(c);
            }
        }
        return hasDigit ? sb.toString() : "-";
    }
}

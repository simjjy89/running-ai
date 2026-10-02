package com.runningai.recovery;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure baseline arithmetic. All values are synthetic. */
class RecoveryBaselineServiceTest {

    private static final LocalDate TARGET = LocalDate.of(2026, 10, 2);

    private final Map<LocalDate, RecoveryDailyValues> days = new HashMap<>();

    private static RecoveryDailyValues rhr(int bpm) {
        return new RecoveryDailyValues(null, null, null, null, null, bpm, null, null, null, null, null, null);
    }

    private static RecoveryDailyValues hrv(double ms) {
        return new RecoveryDailyValues(ms, null, null, null, null, null, null, null, null, null, null, null);
    }

    private void rhrDaysBefore(LocalDate day, int count, int bpm) {
        for (int i = 1; i <= count; i++) {
            days.put(day.minusDays(i), rhr(bpm));
        }
    }

    private RecoveryMetricBaseline restingHr() {
        return RecoveryBaselineService.compute(TARGET, days).metric(RecoveryMetric.RESTING_HEART_RATE_BPM).orElseThrow();
    }

    @Test
    void currentValueIsComparedWithTheMeanOfThePreviousDays() {
        rhrDaysBefore(TARGET, 10, 50);
        days.put(TARGET, rhr(55));

        RecoveryMetricBaseline b = restingHr();

        assertThat(b.status()).isEqualTo(BaselineStatus.AVAILABLE);
        assertThat(b.date()).isEqualTo(TARGET);
        assertThat(b.ageDays()).isZero();
        assertThat(b.current()).isEqualTo(55.0);
        assertThat(b.baseline()).isEqualTo(50.0);
        assertThat(b.difference()).isEqualTo(5.0);
        assertThat(b.differencePercent()).isEqualTo(10.0);
        assertThat(b.sampleCount()).isEqualTo(10);
        assertThat(b.windowDays()).isEqualTo(28);
        assertThat(b.minimumSamples()).isEqualTo(7);
    }

    @Test
    void sixValidDaysAreInsufficientSevenAreEnough() {
        rhrDaysBefore(TARGET, 6, 50);
        days.put(TARGET, rhr(52));

        RecoveryMetricBaseline six = restingHr();
        assertThat(six.status()).isEqualTo(BaselineStatus.INSUFFICIENT_DATA);
        assertThat(six.sampleCount()).isEqualTo(6);
        assertThat(six.current()).isEqualTo(52.0);
        assertThat(six.baseline()).isNull();
        assertThat(six.difference()).isNull();
        assertThat(six.differencePercent()).isNull();

        days.put(TARGET.minusDays(7), rhr(50));
        assertThat(restingHr().status()).isEqualTo(BaselineStatus.AVAILABLE);
        assertThat(restingHr().sampleCount()).isEqualTo(7);
    }

    @Test
    void theCurrentDayIsNotPartOfItsOwnBaseline() {
        rhrDaysBefore(TARGET, 7, 50);
        days.put(TARGET, rhr(80));

        assertThat(restingHr().baseline()).isEqualTo(50.0);
    }

    @Test
    void daysWithoutAValueAreSkippedNeverCountedAsZero() {
        rhrDaysBefore(TARGET, 7, 50);
        days.put(TARGET.minusDays(8), RecoveryDailyValues.EMPTY);
        days.put(TARGET.minusDays(9), hrv(40.0));   // another metric only
        days.put(TARGET, rhr(50));

        RecoveryMetricBaseline b = restingHr();

        assertThat(b.sampleCount()).isEqualTo(7);
        assertThat(b.baseline()).isEqualTo(50.0);
    }

    @Test
    void onlyTheTwentyEightDaysBeforeTheCurrentValueFormTheBaseline() {
        rhrDaysBefore(TARGET, 28, 50);
        days.put(TARGET.minusDays(29), rhr(200));   // outside the window
        days.put(TARGET, rhr(50));

        RecoveryMetricBaseline b = restingHr();

        assertThat(b.sampleCount()).isEqualTo(28);
        assertThat(b.baseline()).isEqualTo(50.0);
    }

    @Test
    void aStaleValueIsReportedWithItsAgeAndItsOwnBaselineWindow() {
        LocalDate lastReading = TARGET.minusDays(5);
        rhrDaysBefore(lastReading, 8, 48);
        days.put(lastReading, rhr(54));

        RecoveryMetricBaseline b = restingHr();

        assertThat(b.date()).isEqualTo(lastReading);
        assertThat(b.ageDays()).isEqualTo(5);
        assertThat(b.current()).isEqualTo(54.0);
        assertThat(b.baseline()).isEqualTo(48.0);
    }

    @Test
    void aMetricWithNoReadingInTheLookupWindowIsAbsentNotZero() {
        days.put(TARGET.minusDays(28), rhr(50));   // just outside the 28-day lookup window

        RecoveryBaselines baselines = RecoveryBaselineService.compute(TARGET, days);

        assertThat(baselines.metrics()).isEmpty();
        assertThat(baselines.metric(RecoveryMetric.HRV_LAST_NIGHT_AVG_MS)).isEmpty();
    }

    @Test
    void valuesAfterTheTargetDateAreIgnored() {
        rhrDaysBefore(TARGET, 7, 50);
        days.put(TARGET.plusDays(1), rhr(99));

        RecoveryMetricBaseline b = restingHr();

        assertThat(b.date()).isEqualTo(TARGET.minusDays(1));
        assertThat(RecoveryBaselineService.compute(TARGET, days).days()).doesNotContainKey(TARGET.plusDays(1));
    }

    @Test
    void aZeroBaselineHasNoPercentage() {
        for (int i = 1; i <= 7; i++) {
            days.put(TARGET.minusDays(i), new RecoveryDailyValues(null, null, null, null, null, null, null, null, null, null, 0, null));
        }
        days.put(TARGET, new RecoveryDailyValues(null, null, null, null, null, null, null, null, null, null, 10, null));

        RecoveryMetricBaseline b = RecoveryBaselineService.compute(TARGET, days).metric(RecoveryMetric.STRESS_AVERAGE).orElseThrow();

        assertThat(b.baseline()).isEqualTo(0.0);
        assertThat(b.difference()).isEqualTo(10.0);
        assertThat(b.differencePercent()).isNull();
    }

    @Test
    void resultsAreRoundedToOneDecimal() {
        days.put(TARGET.minusDays(1), hrv(50.0));
        days.put(TARGET.minusDays(2), hrv(51.0));
        days.put(TARGET.minusDays(3), hrv(51.0));
        for (int i = 4; i <= 7; i++) {
            days.put(TARGET.minusDays(i), hrv(50.0));
        }
        days.put(TARGET, hrv(45.0));

        RecoveryMetricBaseline b = RecoveryBaselineService.compute(TARGET, days).metric(RecoveryMetric.HRV_LAST_NIGHT_AVG_MS).orElseThrow();

        assertThat(b.baseline()).isEqualTo(50.3);        // 352 / 7 = 50.2857
        assertThat(b.difference()).isEqualTo(-5.3);
        assertThat(b.differencePercent()).isEqualTo(-10.5);
    }

    @Test
    void eachMetricHasItsOwnCurrentDate() {
        rhrDaysBefore(TARGET, 7, 50);
        days.put(TARGET.minusDays(2), new RecoveryDailyValues(45.0, null, null, null, null, 50, null, null, null, null, null, null));

        RecoveryBaselines baselines = RecoveryBaselineService.compute(TARGET, days);

        assertThat(baselines.metric(RecoveryMetric.RESTING_HEART_RATE_BPM).orElseThrow().date()).isEqualTo(TARGET.minusDays(1));
        assertThat(baselines.metric(RecoveryMetric.HRV_LAST_NIGHT_AVG_MS).orElseThrow().date()).isEqualTo(TARGET.minusDays(2));
        assertThat(baselines.metric(RecoveryMetric.HRV_LAST_NIGHT_AVG_MS).orElseThrow().status())
                .isEqualTo(BaselineStatus.INSUFFICIENT_DATA);
    }
}

package com.runningai.training;

import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ActivityType;
import com.runningai.activity.ExternalSource;
import com.runningai.athlete.AthleteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Training state over the real activity table. asOf = 2026-09-30 (Wednesday, Asia/Seoul):
 * current 7 days = 09-24..09-30, previous 7 days = 09-17..09-23, chronic 28 days = 09-03..09-30.
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class TrainingStateServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final double TOL = 1e-9;

    @Autowired
    private TrainingStateService service;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private AthleteService athleteService;

    private Long athleteId;

    @BeforeEach
    void setUp() {
        athleteId = athleteService.getDefaultAthlete().getId();
    }

    private static Instant kst(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL).toInstant();
    }

    private void save(ActivityType type, Instant startedAt, int minutes, Double distanceMeters) {
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "ts-" + IDS.incrementAndGet(),
                type, startedAt, minutes * 60, distanceMeters, null, null));
    }

    /** A 06:00 local run of {@code minutes} on the given day, 100 m per minute. */
    private void runOn(LocalDate day, int minutes) {
        save(ActivityType.RUN, day.atTime(6, 0).atZone(SEOUL).toInstant(), minutes, minutes * 100.0);
    }

    /** Daily run minutes for the 7 days ending 2026-09-30, oldest first (index 0 = 09-24). */
    private void currentWeek(int... minutes) {
        for (int i = 0; i < minutes.length; i++) {
            if (minutes[i] > 0) {
                runOn(AS_OF.minusDays(6 - i), minutes[i]);
            }
        }
    }

    /** Daily run minutes for the previous 7 days, oldest first (index 0 = 09-17). */
    private void previousWeek(int... minutes) {
        for (int i = 0; i < minutes.length; i++) {
            if (minutes[i] > 0) {
                runOn(AS_OF.minusDays(13 - i), minutes[i]);
            }
        }
    }

    @Test
    void emptyHistory() {
        TrainingState s = service.state(AS_OF);

        assertThat(s).isEqualTo(new TrainingState(AS_OF, 0, 0, null, 0, 0, null, 0, 0, null, 0, 0, null, 0, null, null, 0, 7));
    }

    @Test
    void constantDailyLoadHasNoDefinedMonotonyOrStrain() {
        currentWeek(10, 10, 10, 10, 10, 10, 10);

        TrainingState s = service.state(AS_OF);

        assertThat(s.current7DayLoad()).isEqualTo(70.0);
        assertThat(s.monotony()).isNull();     // SD = 0
        assertThat(s.strain()).isNull();
        assertThat(s.activeDays7Days()).isEqualTo(7);
        assertThat(s.restDays7Days()).isZero();
    }

    @Test
    void constantNonRoundLoadIsAlsoZeroDeviation() {
        // 100 s per day is not a whole number of minutes; the deviation must still be exactly 0
        for (int i = 0; i < 7; i++) {
            save(ActivityType.RUN, AS_OF.minusDays(i).atTime(6, 0).atZone(SEOUL).toInstant(), 0, 0.0);
            activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "ts-c" + i, ActivityType.RUN,
                    AS_OF.minusDays(i).atTime(9, 0).atZone(SEOUL).toInstant(), 100, null, null, null));
        }

        assertThat(service.state(AS_OF).monotony()).isNull();
    }

    @Test
    void variableWeekMonotonyStrainAndDayCounts() {
        currentWeek(0, 30, 0, 60, 30, 0, 90);      // 09-24 .. 09-30

        TrainingState s = service.state(AS_OF);

        double mean = 210 / 7.0;                                   // 30
        double populationSd = Math.sqrt((900 + 0 + 900 + 900 + 0 + 900 + 3600) / 7.0);   // divide by N
        assertThat(s.acuteLoad()).isEqualTo(210.0);
        assertThat(s.monotony()).isCloseTo(mean / populationSd, within(TOL));
        assertThat(s.monotony()).isCloseTo(0.93541, within(1e-4));
        assertThat(s.strain()).isCloseTo(210.0 * mean / populationSd, within(TOL));
        assertThat(s.activeDays7Days()).isEqualTo(4);
        assertThat(s.restDays7Days()).isEqualTo(3);
    }

    @Test
    void sampleStandardDeviationIsNotUsed() {
        currentWeek(0, 30, 0, 60, 30, 0, 90);

        double sampleSd = Math.sqrt(7200 / 6.0);
        assertThat(service.state(AS_OF).monotony()).isNotCloseTo(30.0 / sampleSd, within(1e-6));
    }

    @Test
    void severalActivitiesOnOneDayAreOneActiveDay() {
        save(ActivityType.RUN, kst("2026-09-30T06:00:00"), 30, 5000.0);
        save(ActivityType.INDOOR_CYCLING, kst("2026-09-30T20:00:00"), 45, null);

        TrainingState s = service.state(AS_OF);

        assertThat(s.current7DayLoad()).isEqualTo(75.0);
        assertThat(s.activeDays7Days()).isEqualTo(1);
        assertThat(s.restDays7Days()).isEqualTo(6);
    }

    @Test
    void acuteChronicAndRatioFromA28DayHistory() {
        // 21 days x 20 min (09-03..09-23) then 7 days x 60 min (09-24..09-30)
        for (int i = 27; i >= 7; i--) {
            runOn(AS_OF.minusDays(i), 20);
        }
        for (int i = 6; i >= 0; i--) {
            runOn(AS_OF.minusDays(i), 60);
        }

        TrainingState s = service.state(AS_OF);

        assertThat(s.acuteLoad()).isEqualTo(420.0);                       // last 7 days
        assertThat(s.chronicLoad()).isEqualTo((21 * 20 + 7 * 60) / 4.0);  // 28-day total / 4 = 210
        assertThat(s.acuteChronicRatio()).isCloseTo(2.0, within(TOL));
        assertThat(s.previous7DayLoad()).isEqualTo(140.0);
        assertThat(s.rampLoad()).isEqualTo(280.0);
    }

    @Test
    void ratioIsReportedAsComputedWithoutInterpretation() {
        currentWeek(0, 0, 0, 0, 0, 0, 100);        // nothing in the 21 days before the current week

        TrainingState s = service.state(AS_OF);

        assertThat(s.acuteLoad()).isEqualTo(100.0);
        assertThat(s.chronicLoad()).isEqualTo(25.0);
        assertThat(s.acuteChronicRatio()).isCloseTo(4.0, within(TOL));
    }

    @Test
    void ratioIsNullWithoutAChronicBaseline() {
        assertThat(service.state(AS_OF).acuteChronicRatio()).isNull();
    }

    @Test
    void positiveWeeklyProgression() {
        previousWeek(0, 0, 0, 0, 0, 0, 100);
        currentWeek(0, 0, 0, 0, 0, 0, 120);

        TrainingState s = service.state(AS_OF);

        assertThat(s.previous7DayLoad()).isEqualTo(100.0);
        assertThat(s.current7DayLoad()).isEqualTo(120.0);
        assertThat(s.weeklyLoadChangePercent()).isCloseTo(20.0, within(TOL));
        assertThat(s.rampLoad()).isEqualTo(20.0);
    }

    @Test
    void negativeWeeklyProgression() {
        previousWeek(100, 100, 0, 0, 0, 0, 0);
        currentWeek(0, 0, 0, 0, 0, 0, 150);

        TrainingState s = service.state(AS_OF);

        assertThat(s.weeklyLoadChangePercent()).isCloseTo(-25.0, within(TOL));
        assertThat(s.rampLoad()).isEqualTo(-50.0);
    }

    @Test
    void previousZeroMakesPercentChangesUndefined() {
        currentWeek(0, 0, 0, 0, 0, 0, 100);

        TrainingState s = service.state(AS_OF);

        assertThat(s.previous7DayLoad()).isZero();
        assertThat(s.weeklyLoadChangePercent()).isNull();
        assertThat(s.runningDistanceChangePercent()).isNull();
        assertThat(s.runningDurationChangePercent()).isNull();
        assertThat(s.rampLoad()).isEqualTo(100.0);
    }

    @Test
    void bothWeeksZeroIsUndefinedNotZeroPercent() {
        TrainingState s = service.state(AS_OF);

        assertThat(s.weeklyLoadChangePercent()).isNull();
        assertThat(s.rampLoad()).isZero();
    }

    @Test
    void runningDistanceAndDurationProgressionExcludeCycling() {
        // previous week: 10 km run (100 min); current week: 12 km treadmill (60 min) plus a long ride that must not count
        save(ActivityType.RUN, kst("2026-09-20T06:00:00"), 100, 10000.0);
        save(ActivityType.TREADMILL_RUN, kst("2026-09-28T19:00:00"), 60, 12000.0);
        save(ActivityType.INDOOR_CYCLING, kst("2026-09-29T20:00:00"), 120, 60000.0);
        save(ActivityType.INDOOR_CYCLING, kst("2026-09-21T20:00:00"), 90, 40000.0);

        TrainingState s = service.state(AS_OF);

        assertThat(s.runningDistance7DaysMeters()).isEqualTo(12000.0);
        assertThat(s.previousRunningDistance7DaysMeters()).isEqualTo(10000.0);
        assertThat(s.runningDistanceChangePercent()).isCloseTo(20.0, within(TOL));
        assertThat(s.runningDuration7DaysSeconds()).isEqualTo(3600);
        assertThat(s.previousRunningDuration7DaysSeconds()).isEqualTo(6000);
        assertThat(s.runningDurationChangePercent()).isCloseTo(-40.0, within(TOL));
        assertThat(s.current7DayLoad()).isEqualTo(180.0);                   // cycling does count towards load
        assertThat(s.previous7DayLoad()).isEqualTo(190.0);
    }

    @Test
    void windowsFollowTheAthleteLocalDayAcrossTheUtcBoundary() {
        // 09-23T15:00Z = 09-24 00:00 Seoul: first instant of the current window
        save(ActivityType.RUN, Instant.parse("2026-09-23T15:00:00Z"), 10, 1000.0);
        // 09-23T14:59:59Z = 09-23 23:59:59 Seoul: last second of the previous window
        save(ActivityType.RUN, Instant.parse("2026-09-23T14:59:59Z"), 20, 2000.0);
        // 09-16T15:00:00Z = 09-17 00:00 Seoul: first instant of the previous window
        save(ActivityType.RUN, Instant.parse("2026-09-16T15:00:00Z"), 30, 3000.0);
        // 09-16T14:59:59Z = 09-16 23:59:59 Seoul: before the previous window (still in the 28-day baseline)
        save(ActivityType.RUN, Instant.parse("2026-09-16T14:59:59Z"), 40, 4000.0);
        // 09-29T15:30Z = 09-30 00:30 Seoul: inside the current window although its UTC date is 09-29
        save(ActivityType.RUN, Instant.parse("2026-09-29T15:30:00Z"), 50, 5000.0);

        TrainingState s = service.state(AS_OF);

        assertThat(s.current7DayLoad()).isEqualTo(60.0);       // 10 + 50
        assertThat(s.previous7DayLoad()).isEqualTo(50.0);      // 20 + 30
        assertThat(s.chronicLoad()).isEqualTo((10 + 20 + 30 + 40 + 50) / 4.0);
    }

    @Test
    void activityAfterTheAsOfDayIsExcluded() {
        save(ActivityType.RUN, kst("2026-09-30T23:59:59"), 10, 1000.0);    // last second of asOf: included
        save(ActivityType.RUN, kst("2026-10-01T00:00:00"), 500, 9999.0);   // next local day: excluded

        TrainingState s = service.state(AS_OF);

        assertThat(s.current7DayLoad()).isEqualTo(10.0);
        assertThat(s.chronicLoad()).isEqualTo(10.0 / 4.0);
    }

    @Test
    void earlierAsOfDateShiftsEveryWindow() {
        currentWeek(0, 0, 0, 0, 0, 0, 100);                          // 09-30 only

        TrainingState earlier = service.state(LocalDate.of(2026, 9, 29));

        assertThat(earlier.asOfDate()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(earlier.current7DayLoad()).isZero();              // the 09-30 run is in the future
        assertThat(earlier.activeDays7Days()).isZero();
    }

    @Test
    void repeatedCalculationIsDeterministic() {
        currentWeek(20, 0, 45, 30, 0, 60, 10);
        previousWeek(15, 15, 0, 40, 0, 0, 25);

        assertThat(service.state(AS_OF)).isEqualTo(service.state(AS_OF));
    }
}

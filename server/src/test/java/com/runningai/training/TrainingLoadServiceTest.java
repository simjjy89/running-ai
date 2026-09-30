package com.runningai.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRawService;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Training load over the real activity table (H2 + Flyway). The athlete timezone is Asia/Seoul (UTC+9,
 * no DST), so Korean local times below are written explicitly and converted to UTC instants.
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class TrainingLoadServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired
    private TrainingLoadService service;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ActivityRawService activityRawService;

    @Autowired
    private AthleteService athleteService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long athleteId;

    @BeforeEach
    void setUp() {
        athleteId = athleteService.getDefaultAthlete().getId();
    }

    /** Local Asia/Seoul wall time -> UTC instant. */
    private static Instant kst(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SEOUL).toInstant();
    }

    private void save(ActivityType type, Instant startedAt, int seconds, Double distanceMeters) {
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "tl-" + IDS.incrementAndGet(),
                type, startedAt, seconds, distanceMeters, null, null));
    }

    private void run(String kstStart, int minutes, double km) {
        save(ActivityType.RUN, kst(kstStart), minutes * 60, km * 1000);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);   // a Wednesday

    @Test
    void emptyHistoryIsAllZero() {
        TrainingLoadSummary s = service.summary(AS_OF);

        assertThat(s).isEqualTo(new TrainingLoadSummary(AS_OF, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(service.weekly(AS_OF)).isEqualTo(new WeeklyTrainingSummary(
                LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4), 0, 0, 0, 0, 0));
    }

    @Test
    void singleRunIsOneLoadMinutePerMinute() {
        run("2026-09-30T06:30:00", 60, 10);

        TrainingLoadSummary s = service.summary(AS_OF);

        assertThat(s.load7Days()).isEqualTo(60.0);
        assertThat(s.load28Days()).isEqualTo(60.0);
        assertThat(s.runningDuration7DaysSeconds()).isEqualTo(3600);
        assertThat(s.runningDistance7DaysMeters()).isEqualTo(10000.0);
        assertThat(s.activityCount7Days()).isEqualTo(1);
        assertThat(s.activityCount28Days()).isEqualTo(1);
    }

    @Test
    void treadmillCountsAsRunning() {
        save(ActivityType.TREADMILL_RUN, kst("2026-09-29T19:00:00"), 1800, 5000.0);

        TrainingLoadSummary s = service.summary(AS_OF);

        assertThat(s.load7Days()).isEqualTo(30.0);
        assertThat(s.runningDuration7DaysSeconds()).isEqualTo(1800);
        assertThat(s.runningDistance7DaysMeters()).isEqualTo(5000.0);
    }

    @Test
    void indoorCyclingAddsLoadAndCyclingDurationButNoRunningMetrics() {
        run("2026-09-29T06:00:00", 30, 6);
        save(ActivityType.INDOOR_CYCLING, kst("2026-09-30T20:00:00"), 45 * 60, 25000.0);

        TrainingLoadSummary s = service.summary(AS_OF);
        DailyTrainingLoad day = service.daily(AS_OF, AS_OF).get(0);

        assertThat(s.load7Days()).isEqualTo(75.0);
        assertThat(s.runningDuration7DaysSeconds()).isEqualTo(1800);          // unchanged by cycling
        assertThat(s.runningDistance7DaysMeters()).isEqualTo(6000.0);         // cycling distance excluded
        assertThat(s.activityCount7Days()).isEqualTo(2);
        assertThat(day.cyclingDurationSeconds()).isEqualTo(2700);
        assertThat(day.trainingLoadMinutes()).isEqualTo(45.0);
    }

    @Test
    void mixedWeekAggregatesPerType() {
        run("2026-09-28T06:00:00", 60, 10);                                    // Mon
        save(ActivityType.TREADMILL_RUN, kst("2026-09-30T19:00:00"), 40 * 60, 7000.0);   // Wed
        save(ActivityType.INDOOR_CYCLING, kst("2026-10-02T21:00:00"), 2500, null);       // Fri, no distance
        run("2026-10-04T07:00:00", 20, 4);                                     // Sun
        run("2026-09-27T07:00:00", 100, 20);                                   // previous Sunday: other week

        WeeklyTrainingSummary w = service.weekly(LocalDate.of(2026, 10, 1));   // any day inside the week

        assertThat(w.weekStart()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(w.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(w.activityCount()).isEqualTo(4);
        assertThat(w.trainingLoadMinutes()).isCloseTo((3600 + 2400 + 2500 + 1200) / 60.0, within(1e-9));
        assertThat(w.runningDistanceMeters()).isEqualTo(21000.0);
        assertThat(w.runningDurationSeconds()).isEqualTo(3600 + 2400 + 1200);
        assertThat(w.cyclingDurationSeconds()).isEqualTo(2500);
    }

    @Test
    void secondsAreNotRoundedToMinutes() {
        save(ActivityType.RUN, kst("2026-09-30T06:00:00"), 100, 300.0);       // 1 min 40 s

        assertThat(service.summary(AS_OF).load7Days()).isCloseTo(100 / 60.0, within(1e-12));
    }

    @Test
    void sevenDayWindowIsHalfOpenAndLocal() {
        // asOf 2026-09-30 -> [2026-09-24 00:00, 2026-10-01 00:00) in Asia/Seoul
        run("2026-09-23T23:59:59", 10, 1);    // excluded (one second early)
        run("2026-09-24T00:00:00", 20, 2);    // included: exactly 7 days back at 00:00
        run("2026-09-30T23:59:59", 30, 3);    // included: last second of asOf day
        run("2026-10-01T00:00:00", 40, 4);    // excluded: next day 00:00

        TrainingLoadSummary s = service.summary(AS_OF);

        assertThat(s.load7Days()).isEqualTo(50.0);
        assertThat(s.activityCount7Days()).isEqualTo(2);
        assertThat(s.runningDistance7DaysMeters()).isEqualTo(5000.0);
    }

    @Test
    void twentyEightDayWindowIsHalfOpenAndLocal() {
        // asOf 2026-09-30 -> [2026-09-03 00:00, 2026-10-01 00:00)
        run("2026-09-02T23:59:59", 10, 1);    // excluded
        run("2026-09-03T00:00:00", 20, 2);    // included (28 days back)
        run("2026-09-24T00:00:00", 30, 3);    // inside both windows
        run("2026-10-01T00:00:00", 40, 4);    // excluded

        TrainingLoadSummary s = service.summary(AS_OF);

        assertThat(s.load28Days()).isEqualTo(50.0);
        assertThat(s.activityCount28Days()).isEqualTo(2);
        assertThat(s.load7Days()).isEqualTo(30.0);
        assertThat(s.activityCount7Days()).isEqualTo(1);
    }

    @Test
    void groupsByAthleteLocalDayNotUtcDay() {
        // 2026-09-29T15:30Z is 2026-09-30 00:30 in Seoul; 2026-09-29T14:59:59Z is 2026-09-29 23:59:59 in Seoul
        save(ActivityType.RUN, Instant.parse("2026-09-29T15:30:00Z"), 3600, 10000.0);
        save(ActivityType.RUN, Instant.parse("2026-09-29T14:59:59Z"), 1800, 5000.0);

        List<DailyTrainingLoad> days = service.daily(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30));

        assertThat(days).extracting(DailyTrainingLoad::date).containsExactly(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30));
        assertThat(days.get(0).trainingLoadMinutes()).isEqualTo(30.0);
        assertThat(days.get(1).trainingLoadMinutes()).isEqualTo(60.0);
        assertThat(days.get(1).runningDistanceMeters()).isEqualTo(10000.0);
    }

    @Test
    void otherTimezoneGroupsDifferently() {
        save(ActivityType.RUN, Instant.parse("2026-09-29T15:30:00Z"), 3600, 10000.0);

        List<DailyTrainingLoad> utc = service.daily(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30), ZoneId.of("UTC"));

        assertThat(utc.get(0).activityCount()).isEqualTo(1);       // 09-29 in UTC
        assertThat(utc.get(1).activityCount()).isZero();
        assertThat(service.summary(LocalDate.of(2026, 9, 29), ZoneId.of("UTC")).activityCount7Days()).isEqualTo(1);
    }

    @Test
    void sundayNightAndMondayMorningFallInDifferentWeeks() {
        run("2026-09-27T23:59:00", 30, 5);    // Sunday 23:59 local -> week of 2026-09-21
        run("2026-09-28T00:00:00", 40, 6);    // Monday 00:00 local -> week of 2026-09-28

        WeeklyTrainingSummary previous = service.weekly(LocalDate.of(2026, 9, 27));
        WeeklyTrainingSummary current = service.weekly(LocalDate.of(2026, 9, 28));

        assertThat(previous.weekStart()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(previous.weekEnd()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(previous.trainingLoadMinutes()).isEqualTo(30.0);
        assertThat(current.weekStart()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(current.trainingLoadMinutes()).isEqualTo(40.0);
    }

    @Test
    void sundayIsTheLastDayOfItsIsoWeek() {
        WeeklyTrainingSummary w = service.weekly(LocalDate.of(2026, 10, 4));   // Sunday

        assertThat(w.weekStart()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(w.weekEnd()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    void zeroDurationAndMissingDistanceAreCountedWithoutLoad() {
        save(ActivityType.RUN, kst("2026-09-30T06:00:00"), 0, 1000.0);        // zero duration
        save(ActivityType.RUN, kst("2026-09-30T07:00:00"), 600, null);        // no distance

        TrainingLoadSummary s = service.summary(AS_OF);

        assertThat(s.activityCount7Days()).isEqualTo(2);
        assertThat(s.load7Days()).isEqualTo(10.0);
        assertThat(s.runningDistance7DaysMeters()).isEqualTo(1000.0);
    }

    @Test
    void dailyIncludesEmptyDaysInOrder() {
        run("2026-09-30T06:00:00", 10, 1);

        List<DailyTrainingLoad> days = service.daily(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30));

        assertThat(days).hasSize(3);
        assertThat(days.get(0).activityCount()).isZero();
        assertThat(days.get(1).activityCount()).isZero();
        assertThat(days.get(2).activityCount()).isEqualTo(1);
    }

    @Test
    void rawOnlyActivityIsNotCounted() throws Exception {
        // a stored Garmin payload without a normalised Activity (for example an unsupported type)
        activityRawService.saveOrUpdate(ExternalSource.GARMIN, "raw-only-1",
                objectMapper.readTree("{\"activityId\": 1, \"duration\": 3600.0, \"distance\": 10000.0}"), Instant.now());

        TrainingLoadSummary s = service.summary(AS_OF);

        assertThat(s.activityCount28Days()).isZero();
        assertThat(s.load28Days()).isZero();
    }

    @Test
    void calculationIsDeterministic() {
        run("2026-09-29T06:00:00", 45, 8);
        save(ActivityType.INDOOR_CYCLING, kst("2026-09-25T19:00:00"), 3000, null);

        assertThat(service.summary(AS_OF)).isEqualTo(service.summary(AS_OF));
        assertThat(service.weekly(AS_OF)).isEqualTo(service.weekly(AS_OF));
    }

    @Test
    void todayIsTheAthleteLocalDateOfTheClock() {
        // fixed clock 2026-09-29T16:00Z: UTC date is 09-29, Seoul date is 09-30
        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 9, 30));
    }
}

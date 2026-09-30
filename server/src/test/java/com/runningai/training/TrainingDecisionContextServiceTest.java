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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.runningai.training.CandidateTrainingType.CROSS_TRAINING;
import static com.runningai.training.CandidateTrainingType.EASY;
import static com.runningai.training.CandidateTrainingType.LONG;
import static com.runningai.training.CandidateTrainingType.QUALITY;
import static com.runningai.training.CandidateTrainingType.RECOVERY;
import static com.runningai.training.CandidateTrainingType.REST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision context over the real activity table. asOf = 2026-09-30 (Wednesday, Asia/Seoul);
 * the history window is 09-03..09-30 and the default pattern window 09-17..09-30.
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class TrainingDecisionContextServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired
    private TrainingDecisionContextService service;

    @Autowired
    private TrainingStateService stateService;

    @Autowired
    private TrainingLoadService loadService;

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

    private void save(ActivityType type, Instant startedAt, int minutes, Double distanceMeters, Integer avgHr, Integer maxHr) {
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "dc-" + IDS.incrementAndGet(),
                type, startedAt, minutes * 60, distanceMeters, avgHr, maxHr));
    }

    private void runOn(LocalDate day, int minutes) {
        save(ActivityType.RUN, day.atTime(6, 0).atZone(SEOUL).toInstant(), minutes, minutes * 100.0, null, null);
    }

    private void cycleOn(LocalDate day, int minutes) {
        save(ActivityType.INDOOR_CYCLING, day.atTime(19, 0).atZone(SEOUL).toInstant(), minutes, null, null, null);
    }

    private static LocalDate day(int daysBeforeAsOf) {
        return AS_OF.minusDays(daysBeforeAsOf);
    }

    private static DailyTrainingPattern patternOn(TrainingDecisionContext c, LocalDate date) {
        return c.recentPattern().stream().filter(p -> p.date().equals(date)).findFirst().orElseThrow();
    }

    // ---- empty / limited history -------------------------------------------------------------

    @Test
    void emptyHistoryHasMinimalCandidatesAndUnknownTrend() {
        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.asOfDate()).isEqualTo(AS_OF);
        assertThat(c.reasons()).contains(DecisionReason.LIMITED_HISTORY, DecisionReason.NO_RECENT_RUNNING);
        assertThat(c.candidateTrainingTypes()).containsExactly(REST, EASY, CROSS_TRAINING);
        assertThat(c.loadTrend()).isEqualTo(LoadTrend.UNKNOWN);
        assertThat(c.lastRunningDate()).isNull();
        assertThat(c.daysSinceRunning()).isNull();
        assertThat(c.lastActiveDate()).isNull();
        assertThat(c.daysSinceActive()).isNull();
        assertThat(c.lastLongRunDate()).isNull();
        assertThat(c.daysSinceLongRun()).isNull();
        assertThat(c.consecutiveActiveDays()).isZero();
        assertThat(c.consecutiveRestDays()).isEqualTo(28);
        assertThat(c.recentPattern()).hasSize(14)
                .allSatisfy(p -> {
                    assertThat(p.classification()).isEqualTo(SessionClassification.REST);
                    assertThat(p.classificationReason()).isEqualTo(ClassificationReason.NO_ACTIVITY);
                    assertThat(p.totalLoadMinutes()).isZero();
                });
    }

    @Test
    void recentPatternIsOldestFirstConsecutiveAndEndsOnAsOf() {
        TrainingDecisionContext c = service.context(AS_OF);
        assertThat(c.recentPattern().get(0).date()).isEqualTo(AS_OF.minusDays(13));
        assertThat(c.recentPattern().get(13).date()).isEqualTo(AS_OF);
        for (int i = 1; i < 14; i++) {
            assertThat(c.recentPattern().get(i).date()).isEqualTo(c.recentPattern().get(i - 1).date().plusDays(1));
        }
    }

    // ---- classification ----------------------------------------------------------------------

    @Test
    void longRunThresholdIsInclusiveAtNinetyMinutes() {
        runOn(day(3), 89);
        runOn(day(2), 90);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(patternOn(c, day(3)).classification()).isEqualTo(SessionClassification.EASY_OR_GENERAL);
        assertThat(patternOn(c, day(3)).classificationReason()).isEqualTo(ClassificationReason.RUNNING_ACTIVITY);
        assertThat(patternOn(c, day(2)).classification()).isEqualTo(SessionClassification.LONG);
        assertThat(patternOn(c, day(2)).classificationReason()).isEqualTo(ClassificationReason.DURATION_THRESHOLD);
        assertThat(c.lastLongRunDate()).isEqualTo(day(2));
        assertThat(c.daysSinceLongRun()).isEqualTo(2);
    }

    @Test
    void longNeedsOneSingleRunNotTheDailySum() {
        runOn(day(1), 50);
        save(ActivityType.RUN, day(1).atTime(18, 0).atZone(SEOUL).toInstant(), 50, 5000.0, null, null);

        DailyTrainingPattern p = patternOn(service.context(AS_OF), day(1));

        assertThat(p.totalLoadMinutes()).isEqualTo(100.0);
        assertThat(p.classification()).isEqualTo(SessionClassification.EASY_OR_GENERAL);
    }

    @Test
    void treadmillRunCountsAsRunningAndCanBeLong() {
        save(ActivityType.TREADMILL_RUN, kst("2026-09-28T07:00:00"), 100, 15000.0, null, null);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(patternOn(c, day(2)).classification()).isEqualTo(SessionClassification.LONG);
        assertThat(c.lastRunningDate()).isEqualTo(day(2));
    }

    @Test
    void sameDayRunAndCyclingSumLoadAndAreRepresentedAsEasyOrGeneral() {
        runOn(AS_OF, 30);
        cycleOn(AS_OF, 45);

        DailyTrainingPattern p = patternOn(service.context(AS_OF), AS_OF);

        assertThat(p.activityCount()).isEqualTo(2);
        assertThat(p.totalLoadMinutes()).isEqualTo(75.0);
        assertThat(p.runningDurationSeconds()).isEqualTo(1800);
        assertThat(p.runningDistanceMeters()).isEqualTo(3000.0);
        assertThat(p.cyclingDurationSeconds()).isEqualTo(2700);
        assertThat(p.classification()).isEqualTo(SessionClassification.EASY_OR_GENERAL);
    }

    @Test
    void cyclingOnlyDayIsIndoorCycling() {
        cycleOn(AS_OF, 45);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(patternOn(c, AS_OF).classification()).isEqualTo(SessionClassification.INDOOR_CYCLING);
        assertThat(patternOn(c, AS_OF).classificationReason()).isEqualTo(ClassificationReason.CYCLING_ONLY);
        assertThat(c.reasons()).contains(DecisionReason.RECENT_CYCLING, DecisionReason.NO_RECENT_RUNNING);
        assertThat(c.lastRunningDate()).isNull();
        assertThat(c.lastActiveDate()).isEqualTo(AS_OF);
    }

    @Test
    void longHasPriorityOverEasyAndCycling() {
        runOn(day(1), 120);
        save(ActivityType.RUN, day(1).atTime(20, 0).atZone(SEOUL).toInstant(), 20, 2000.0, null, null);
        cycleOn(day(1), 30);

        assertThat(patternOn(service.context(AS_OF), day(1)).classification()).isEqualTo(SessionClassification.LONG);
    }

    @Test
    void qualityIsNotAssignedFromHeartRateAlone() {
        // a short, hard-looking run (high average and max HR) must not be labelled a quality session
        save(ActivityType.RUN, kst("2026-09-29T06:00:00"), 45, 9000.0, 178, 195);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.qualityDetectionAvailable()).isFalse();
        assertThat(c.lastQualityDate()).isNull();
        assertThat(c.daysSinceQuality()).isNull();
        assertThat(c.recentPattern()).noneMatch(p -> p.classification() == SessionClassification.QUALITY_CANDIDATE);
        assertThat(patternOn(c, day(1)).classification()).isEqualTo(SessionClassification.EASY_OR_GENERAL);
    }

    // ---- history fields ----------------------------------------------------------------------

    @Test
    void recentLongRunRestrictsCandidatesToRestRecoveryEasy() {
        runOn(day(1), 120);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.daysSinceLongRun()).isEqualTo(1);
        assertThat(c.lastLongRunDate()).isEqualTo(day(1));
        assertThat(c.reasons()).contains(DecisionReason.LONG_RUN_RECENT);
        assertThat(c.candidateTrainingTypes()).containsExactly(REST, RECOVERY, EASY);
        assertThat(c.consecutiveRestDays()).isEqualTo(1);
        assertThat(c.consecutiveActiveDays()).isZero();
    }

    @Test
    void longRunTwoDaysAgoNoLongerRestrictsCandidates() {
        runOn(day(2), 120);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.daysSinceLongRun()).isEqualTo(2);
        assertThat(c.reasons()).doesNotContain(DecisionReason.LONG_RUN_RECENT);
        assertThat(c.candidateTrainingTypes()).containsExactly(EASY, QUALITY, LONG, CROSS_TRAINING);
    }

    @Test
    void threeConsecutiveActiveDaysAreRecordedAndRestrictCandidates() {
        runOn(day(2), 30);
        runOn(day(1), 30);
        runOn(AS_OF, 30);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.consecutiveActiveDays()).isEqualTo(3);
        assertThat(c.consecutiveRestDays()).isZero();
        assertThat(c.reasons()).contains(DecisionReason.MULTIPLE_ACTIVE_DAYS);
        assertThat(c.candidateTrainingTypes()).containsExactly(REST, RECOVERY, EASY);
    }

    @Test
    void twoActiveDaysDoNotTriggerTheMultipleDaysRule() {
        runOn(day(1), 30);
        runOn(AS_OF, 30);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.consecutiveActiveDays()).isEqualTo(2);
        assertThat(c.reasons()).doesNotContain(DecisionReason.MULTIPLE_ACTIVE_DAYS);
        assertThat(c.candidateTrainingTypes()).containsExactly(REST, RECOVERY, EASY, CROSS_TRAINING);
    }

    @Test
    void consecutiveRestDaysAndDaysSinceFields() {
        runOn(day(4), 45);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.consecutiveRestDays()).isEqualTo(4);
        assertThat(c.consecutiveActiveDays()).isZero();
        assertThat(c.lastRunningDate()).isEqualTo(day(4));
        assertThat(c.daysSinceRunning()).isEqualTo(4);
        assertThat(c.lastActiveDate()).isEqualTo(day(4));
        assertThat(c.daysSinceActive()).isEqualTo(4);
        assertThat(c.lastLongRunDate()).isNull();
        assertThat(c.reasons()).contains(DecisionReason.REST_DAY_RECENT);
        assertThat(c.candidateTrainingTypes()).containsExactly(EASY, QUALITY, LONG, CROSS_TRAINING);
    }

    @Test
    void activityOnAsOfDayGivesZeroDaysSince() {
        runOn(AS_OF, 30);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.daysSinceRunning()).isZero();
        assertThat(c.daysSinceActive()).isZero();
        assertThat(c.consecutiveActiveDays()).isEqualTo(1);
        assertThat(c.consecutiveRestDays()).isZero();
        assertThat(c.reasons()).doesNotContain(DecisionReason.REST_DAY_RECENT);
    }

    @Test
    void cyclingCountsAsActiveButNotAsRunning() {
        runOn(day(5), 40);
        cycleOn(day(1), 30);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.daysSinceRunning()).isEqualTo(5);
        assertThat(c.daysSinceActive()).isEqualTo(1);
    }

    @Test
    void lowRecentActivityWhenHistoryExistsButNothingInTheLastWeek() {
        runOn(day(15), 40);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.reasons()).contains(DecisionReason.LOW_RECENT_ACTIVITY);
        assertThat(c.reasons()).doesNotContain(DecisionReason.LIMITED_HISTORY);
        assertThat(c.daysSinceActive()).isEqualTo(15);
    }

    @Test
    void eventsBeyondTheTwentyEightDayHistoryAreNotSearched() {
        runOn(day(28), 120);      // 09-02: one day before the window starts
        assertThat(service.context(AS_OF).lastLongRunDate()).isNull();
        assertThat(service.context(AS_OF).lastRunningDate()).isNull();

        runOn(day(27), 120);      // 09-03: the first day of the window
        TrainingDecisionContext c = service.context(AS_OF);
        assertThat(c.daysSinceLongRun()).isEqualTo(27);
        assertThat(c.daysSinceRunning()).isEqualTo(27);
    }

    // ---- load trend --------------------------------------------------------------------------

    @Test
    void trendLabelsUseTheConfiguredBand() {
        assertThat(TrainingDecisionContextService.trend(15.0, 10)).isEqualTo(LoadTrend.INCREASING);
        assertThat(TrainingDecisionContextService.trend(5.0, 10)).isEqualTo(LoadTrend.STABLE);
        assertThat(TrainingDecisionContextService.trend(-20.0, 10)).isEqualTo(LoadTrend.DECREASING);
        assertThat(TrainingDecisionContextService.trend(null, 10)).isEqualTo(LoadTrend.UNKNOWN);
        assertThat(TrainingDecisionContextService.trend(10.0, 10)).isEqualTo(LoadTrend.STABLE);
        assertThat(TrainingDecisionContextService.trend(-10.0, 10)).isEqualTo(LoadTrend.STABLE);
        assertThat(TrainingDecisionContextService.trend(10.5, 10)).isEqualTo(LoadTrend.INCREASING);
        assertThat(TrainingDecisionContextService.trend(-10.5, 10)).isEqualTo(LoadTrend.DECREASING);
        assertThat(TrainingDecisionContextService.trend(15.0, 20)).isEqualTo(LoadTrend.STABLE);
    }

    @Test
    void trendComesFromTheTrainingStateWeeklyChange() {
        runOn(day(10), 100);      // previous 7 days (09-17..09-23): 100
        runOn(day(3), 115);       // current 7 days (09-24..09-30): 115 -> +15%

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.trainingState().weeklyLoadChangePercent()).isEqualTo(15.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(c.loadTrend()).isEqualTo(LoadTrend.INCREASING);
        assertThat(c.reasons()).contains(DecisionReason.LOAD_INCREASING);
    }

    @Test
    void decreasingTrendAddsItsReason() {
        runOn(day(10), 100);
        runOn(day(3), 80);        // -20%

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.loadTrend()).isEqualTo(LoadTrend.DECREASING);
        assertThat(c.reasons()).contains(DecisionReason.LOAD_DECREASING).doesNotContain(DecisionReason.LOAD_INCREASING);
    }

    @Test
    void embeddedTrainingStateEqualsTheTrainingStateService() {
        runOn(day(10), 100);
        runOn(day(3), 115);
        cycleOn(day(1), 30);

        assertThat(service.context(AS_OF).trainingState()).isEqualTo(stateService.state(AS_OF));
    }

    // ---- candidates: order and duplicates ------------------------------------------------------

    @Test
    void candidatesAndReasonsAreDistinctAndInDeclarationOrderForEveryScenario() {
        // scenario mix: build history, then check several as-of days over it
        runOn(day(9), 120);
        runOn(day(7), 30);
        runOn(day(6), 30);
        runOn(day(5), 30);
        cycleOn(day(5), 30);
        runOn(day(1), 100);
        runOn(AS_OF, 20);

        for (int back = 0; back <= 20; back++) {
            TrainingDecisionContext c = service.context(AS_OF.minusDays(back));
            assertThat(c.candidateTrainingTypes()).doesNotHaveDuplicates().isNotEmpty()
                    .isSortedAccordingTo(Comparator.comparingInt(CandidateTrainingType::ordinal));
            assertThat(c.reasons()).doesNotHaveDuplicates()
                    .isSortedAccordingTo(Comparator.comparingInt(DecisionReason::ordinal));
        }
    }

    @Test
    void sameInputGivesTheSameContext() {
        runOn(day(9), 120);
        runOn(day(1), 100);
        cycleOn(AS_OF, 30);

        assertThat(service.context(AS_OF)).isEqualTo(service.context(AS_OF));
    }

    // ---- timezone and look-ahead ---------------------------------------------------------------

    @Test
    void localMidnightBoundariesFollowTheAthleteTimezone() {
        // 09-29T15:30Z is 09-30 00:30 Seoul (UTC date is 09-29): belongs to asOf
        save(ActivityType.RUN, Instant.parse("2026-09-29T15:30:00Z"), 30, 3000.0, null, null);

        TrainingDecisionContext c = service.context(AS_OF);
        assertThat(c.lastRunningDate()).isEqualTo(AS_OF);
        assertThat(c.daysSinceRunning()).isZero();
        assertThat(patternOn(c, AS_OF).activityCount()).isEqualTo(1);
        assertThat(patternOn(c, day(1)).activityCount()).isZero();
    }

    @Test
    void activityJustAfterLocalMidnightOfTheNextDayIsExcluded() {
        // 09-30T15:00Z = 10-01 00:00 Seoul, exactly the start of the day after asOf
        save(ActivityType.RUN, Instant.parse("2026-09-30T15:00:00Z"), 120, 12000.0, null, null);
        // 09-30T14:59:59Z = 09-30 23:59:59 Seoul, last second of asOf
        save(ActivityType.RUN, Instant.parse("2026-09-30T14:59:59Z"), 20, 2000.0, null, null);

        TrainingDecisionContext c = service.context(AS_OF);

        assertThat(c.lastLongRunDate()).isNull();
        assertThat(patternOn(c, AS_OF).activityCount()).isEqualTo(1);
        assertThat(c.recentPattern().get(13).date()).isEqualTo(AS_OF);
    }

    @Test
    void historicalAsOfDateDoesNotSeeLaterActivities() {
        runOn(day(5), 120);        // 09-25: long run
        runOn(day(1), 120);        // 09-29: later long run
        runOn(AS_OF, 30);

        LocalDate asOf = LocalDate.of(2026, 9, 26);
        TrainingDecisionContext c = service.context(asOf);

        assertThat(c.asOfDate()).isEqualTo(asOf);
        assertThat(c.lastLongRunDate()).isEqualTo(day(5));
        assertThat(c.daysSinceLongRun()).isEqualTo(1);
        assertThat(c.lastActiveDate()).isEqualTo(day(5));
        assertThat(c.recentPattern().get(13).date()).isEqualTo(asOf);
        assertThat(c.recentPattern()).allSatisfy(p -> assertThat(p.date()).isBeforeOrEqualTo(asOf));
        assertThat(c.recentPattern()).noneMatch(p -> p.date().isAfter(asOf));
        assertThat(c.trainingState().asOfDate()).isEqualTo(asOf);
        assertThat(c.trainingState().current7DayLoad()).isEqualTo(120.0);
        assertThat(c.candidateTrainingTypes()).containsExactly(REST, RECOVERY, EASY);
    }

    // ---- configuration -------------------------------------------------------------------------

    @Test
    void configuredLongRunThresholdAndPatternWindowAreUsed() {
        TrainingProperties custom = new TrainingProperties(
                new TrainingProperties.Classification(Duration.ofMinutes(60)),
                new TrainingProperties.Decision(25, 7));
        TrainingDecisionContextService configured = new TrainingDecisionContextService(
                activityRepository, athleteService, loadService, custom);
        runOn(day(1), 60);

        TrainingDecisionContext c = configured.context(AS_OF);

        assertThat(c.recentPattern()).hasSize(7);
        assertThat(patternOn(c, day(1)).classification()).isEqualTo(SessionClassification.LONG);
        assertThat(service.context(AS_OF).recentPattern()).hasSize(14);
        assertThat(patternOn(service.context(AS_OF), day(1)).classification()).isEqualTo(SessionClassification.EASY_OR_GENERAL);
    }

    @Test
    void invalidPropertiesAreRejected() {
        assertThatThrownBy(() -> new TrainingProperties.Classification(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrainingProperties.Classification(Duration.ofMinutes(-5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrainingProperties.Decision(-1, 14)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrainingProperties.Decision(10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrainingProperties.Decision(10, 29)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void todayIsTheAthleteLocalDate() {
        assertThat(service.today()).isEqualTo(AS_OF);
        List<DailyTrainingPattern> pattern = service.context(service.today()).recentPattern();
        assertThat(pattern.get(pattern.size() - 1).date()).isEqualTo(AS_OF);
    }
}

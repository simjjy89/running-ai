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
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static com.runningai.training.CandidateTrainingType.EASY;
import static com.runningai.training.CandidateTrainingType.LONG;
import static com.runningai.training.CandidateTrainingType.QUALITY;
import static com.runningai.training.CandidateTrainingType.RECOVERY;
import static com.runningai.training.CandidateTrainingType.REST;
import static org.assertj.core.api.Assertions.assertThat;

/** Recommendation over the real activity table; asOf = 2026-09-30 (Asia/Seoul), fixed clock 01:00 that day. */
@SpringBootTest
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class WorkoutRecommendationServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired
    private WorkoutRecommendationService service;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private AthleteService athleteService;

    private Long athleteId;

    @BeforeEach
    void setUp() {
        athleteId = athleteService.getDefaultAthlete().getId();
    }

    private void runOn(int daysBeforeAsOf, int minutes) {
        save(ActivityType.RUN, AS_OF.minusDays(daysBeforeAsOf).atTime(6, 0).atZone(SEOUL).toInstant(), minutes);
    }

    private void save(ActivityType type, Instant startedAt, int minutes) {
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "wr-" + IDS.incrementAndGet(),
                type, startedAt, minutes * 60, minutes * 100.0, null, null));
    }

    /** Six active days over 14 with equal load in both weeks (STABLE trend), today and yesterday at rest. */
    private void regularHistory(int previousLongMinutes) {
        runOn(9, 30);
        runOn(11, 30);
        runOn(13, 30);
        runOn(7, previousLongMinutes);     // previous week: previousLongMinutes + 90
        int each = (previousLongMinutes + 90) / 3;    // equal total in both weeks, no run reaches 90 minutes
        runOn(2, each);
        runOn(4, each);
        runOn(6, each);
    }

    @Test
    void emptyHistoryGivesLowEasy() {
        WorkoutRecommendation r = service.recommend(AS_OF);

        assertThat(r.asOfDate()).isEqualTo(AS_OF);
        assertThat(r.recommendedIntent()).isEqualTo(EASY);
        assertThat(r.confidence()).isEqualTo(RecommendationConfidence.LOW);
        assertThat(r.dataSufficiency()).isEqualTo(DataSufficiency.LOW);
        assertThat(r.reasons()).contains(WorkoutRecommendationReason.LIMITED_HISTORY);
        assertThat(r.decisionContext().candidateTrainingTypes()).contains(r.recommendedIntent());
    }

    @Test
    void longRunYesterdayGivesRecovery() {
        runOn(1, 120);

        WorkoutRecommendation r = service.recommend(AS_OF);

        assertThat(r.recommendedIntent()).isEqualTo(RECOVERY);
        assertThat(r.reasons()).contains(WorkoutRecommendationReason.RECENT_LONG_RUN);
        assertThat(r.decisionContext().daysSinceLongRun()).isEqualTo(1);
    }

    @Test
    void longRunAndManyActiveDaysGiveRest() {
        runOn(4, 30);
        runOn(3, 30);
        runOn(2, 30);
        runOn(1, 120);
        runOn(0, 30);

        WorkoutRecommendation r = service.recommend(AS_OF);

        assertThat(r.decisionContext().consecutiveActiveDays()).isEqualTo(5);
        assertThat(r.recommendedIntent()).isEqualTo(REST);
        assertThat(r.durationMinMinutes()).isZero();
        assertThat(r.durationMaxMinutes()).isZero();
        assertThat(r.intensityClass()).isEqualTo(IntensityClass.NONE);
    }

    @Test
    void normalRestedDayWithSufficientHistoryGivesEasyNotQuality() {
        regularHistory(30);

        WorkoutRecommendation r = service.recommend(AS_OF);

        assertThat(r.decisionContext().candidateTrainingTypes()).contains(QUALITY, LONG);
        assertThat(r.decisionContext().loadTrend()).isEqualTo(LoadTrend.STABLE);
        assertThat(r.dataSufficiency()).isEqualTo(DataSufficiency.HIGH);
        assertThat(r.recommendedIntent()).isEqualTo(EASY);
        assertThat(r.confidence()).isEqualTo(RecommendationConfidence.MEDIUM);
        assertThat(r.reasons()).containsExactly(WorkoutRecommendationReason.RECENT_REST,
                WorkoutRecommendationReason.LOAD_TREND_STABLE,
                WorkoutRecommendationReason.QUALITY_HISTORY_UNAVAILABLE,
                WorkoutRecommendationReason.DEFAULT_EASY);
    }

    @Test
    void longRunDueGivesLong() {
        regularHistory(120);      // the 120-minute run 7 days ago is a long run

        WorkoutRecommendation r = service.recommend(AS_OF);

        assertThat(r.decisionContext().daysSinceLongRun()).isEqualTo(7);
        assertThat(r.decisionContext().loadTrend()).isEqualTo(LoadTrend.STABLE);
        assertThat(r.recommendedIntent()).isEqualTo(LONG);
        assertThat(r.reasons()).contains(WorkoutRecommendationReason.LONG_RUN_DUE);
        assertThat(r.durationMinMinutes()).isEqualTo(75);
        assertThat(r.durationMaxMinutes()).isEqualTo(120);
    }

    @Test
    void recommendationIsAlwaysAMemberOfTheCandidatesAndNeverQuality() {
        regularHistory(120);
        runOn(1, 100);
        runOn(0, 20);
        save(ActivityType.INDOOR_CYCLING, AS_OF.minusDays(5).atTime(19, 0).atZone(SEOUL).toInstant(), 40);

        for (int back = 0; back <= 25; back++) {
            WorkoutRecommendation r = service.recommend(AS_OF.minusDays(back));
            assertThat(r.recommendedIntent()).isNotNull().isNotEqualTo(QUALITY);
            assertThat(r.decisionContext().candidateTrainingTypes()).contains(r.recommendedIntent());
            assertThat(r.decisionContext().asOfDate()).isEqualTo(AS_OF.minusDays(back));
        }
    }

    @Test
    void sameInputGivesTheSameRecommendation() {
        regularHistory(120);
        assertThat(service.recommend(AS_OF)).isEqualTo(service.recommend(AS_OF));
    }

    @Test
    void historicalDateIgnoresLaterActivities() {
        runOn(5, 30);             // 09-25
        runOn(1, 120);            // 09-29 long run, in the future for 09-26
        runOn(0, 30);

        WorkoutRecommendation r = service.recommend(LocalDate.of(2026, 9, 26));

        assertThat(r.asOfDate()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(r.decisionContext().lastLongRunDate()).isNull();
        assertThat(r.decisionContext().lastActiveDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(r.reasons()).doesNotContain(WorkoutRecommendationReason.RECENT_LONG_RUN);
        assertThat(r.decisionContext().recentPattern()).allSatisfy(p -> assertThat(p.date()).isBeforeOrEqualTo(LocalDate.of(2026, 9, 26)));
    }

    @Test
    void todayIsTheAthleteLocalDate() {
        // 09-29T15:30Z is 09-30 00:30 Seoul: counts as today's activity
        save(ActivityType.RUN, Instant.parse("2026-09-29T15:30:00Z"), 30);

        assertThat(service.today()).isEqualTo(AS_OF);
        WorkoutRecommendation r = service.recommend(service.today());

        assertThat(r.asOfDate()).isEqualTo(AS_OF);
        assertThat(r.decisionContext().daysSinceActive()).isZero();
        assertThat(r.decisionContext().consecutiveActiveDays()).isEqualTo(1);
    }
}

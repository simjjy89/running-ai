package com.runningai.training;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static com.runningai.training.CandidateTrainingType.CROSS_TRAINING;
import static com.runningai.training.CandidateTrainingType.EASY;
import static com.runningai.training.CandidateTrainingType.LONG;
import static com.runningai.training.CandidateTrainingType.QUALITY;
import static com.runningai.training.CandidateTrainingType.RECOVERY;
import static com.runningai.training.CandidateTrainingType.REST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure rule tests on hand-built contexts (no database). */
class WorkoutRecommendationPolicyTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final TrainingState STATE = TrainingStateService.compute(AS_OF,
            IntStream.range(0, 28).mapToObj(i -> new DailyTrainingLoad(AS_OF.minusDays(27 - i), 0, 0, 0, 0, 0, 0)).toList());

    private static final List<CandidateTrainingType> RESTRICTED = List.of(REST, RECOVERY, EASY);
    private static final List<CandidateTrainingType> RESTED = List.of(EASY, QUALITY, LONG, CROSS_TRAINING);
    private static final List<CandidateTrainingType> ACTIVE_TODAY = List.of(REST, RECOVERY, EASY, CROSS_TRAINING);
    private static final List<CandidateTrainingType> LIMITED = List.of(REST, EASY, CROSS_TRAINING);

    private static TrainingDecisionContext ctx(Integer sinceLong, Integer sinceRun, int active, int rest,
                                               LoadTrend trend, int activeDays14,
                                               List<CandidateTrainingType> candidates, DecisionReason... reasons) {
        List<DailyTrainingPattern> pattern = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            boolean isActive = i < activeDays14;
            pattern.add(new DailyTrainingPattern(AS_OF.minusDays(13 - i),
                    isActive ? SessionClassification.EASY_OR_GENERAL : SessionClassification.REST,
                    isActive ? ClassificationReason.RUNNING_ACTIVITY : ClassificationReason.NO_ACTIVITY,
                    isActive ? 1 : 0, isActive ? 30 : 0, isActive ? 1800 : 0, isActive ? 3000 : 0, 0));
        }
        return new TrainingDecisionContext(AS_OF, STATE, pattern,
                null, sinceRun, null, null, null, sinceLong,
                false, null, null, active, rest, trend, candidates, List.of(reasons));
    }

    /** A regular runner with a defined trend (sufficiency HIGH). */
    private static TrainingDecisionContext regular(Integer sinceLong, Integer sinceRun, int active, int rest,
                                                   LoadTrend trend, List<CandidateTrainingType> candidates) {
        return ctx(sinceLong, sinceRun, active, rest, trend, 8, candidates);
    }

    // ---- restrictive rules ---------------------------------------------------------------------

    @Test
    void longRunYesterdayGivesRecovery() {
        WorkoutRecommendation r = WorkoutRecommendationPolicy.recommend(
                regular(1, 1, 0, 1, LoadTrend.STABLE, RESTRICTED));

        assertThat(r.recommendedIntent()).isEqualTo(RECOVERY);
        assertThat(r.durationMinMinutes()).isEqualTo(20);
        assertThat(r.durationMaxMinutes()).isEqualTo(40);
        assertThat(r.intensityClass()).isEqualTo(IntensityClass.VERY_EASY);
        assertThat(r.confidence()).isEqualTo(RecommendationConfidence.HIGH);
        assertThat(r.reasons()).contains(WorkoutRecommendationReason.RECENT_LONG_RUN, WorkoutRecommendationReason.RECENT_REST);
    }

    @Test
    void longRunAndActiveDaysGiveRest() {
        WorkoutRecommendation r = WorkoutRecommendationPolicy.recommend(
                regular(1, 0, 4, 0, LoadTrend.STABLE, RESTRICTED));

        assertThat(r.recommendedIntent()).isEqualTo(REST);
        assertThat(r.durationMinMinutes()).isZero();
        assertThat(r.durationMaxMinutes()).isZero();
        assertThat(r.intensityClass()).isEqualTo(IntensityClass.NONE);
        assertThat(r.reasons()).contains(WorkoutRecommendationReason.RECENT_LONG_RUN, WorkoutRecommendationReason.MULTIPLE_ACTIVE_DAYS);
    }

    @Test
    void longRunPlusTwoActiveDaysIsEnoughForRest() {
        assertThat(WorkoutRecommendationPolicy.recommend(regular(1, 0, 2, 0, LoadTrend.STABLE, RESTRICTED))
                .recommendedIntent()).isEqualTo(REST);
    }

    @Test
    void fourActiveDaysWithoutLongRunGiveRestButThreeGiveRecovery() {
        assertThat(WorkoutRecommendationPolicy.recommend(regular(null, 0, 4, 0, LoadTrend.STABLE, RESTRICTED))
                .recommendedIntent()).isEqualTo(REST);
        WorkoutRecommendation three = WorkoutRecommendationPolicy.recommend(regular(null, 0, 3, 0, LoadTrend.STABLE, RESTRICTED));
        assertThat(three.recommendedIntent()).isEqualTo(RECOVERY);
        assertThat(three.reasons()).contains(WorkoutRecommendationReason.MULTIPLE_ACTIVE_DAYS);
    }

    @Test
    void restrictiveConfidenceIsCappedWhenHistoryIsVerySparse() {
        WorkoutRecommendation r = WorkoutRecommendationPolicy.recommend(
                ctx(1, 1, 0, 1, LoadTrend.UNKNOWN, 1, RESTRICTED, DecisionReason.LONG_RUN_RECENT));

        assertThat(r.recommendedIntent()).isEqualTo(RECOVERY);
        assertThat(r.dataSufficiency()).isEqualTo(DataSufficiency.LOW);
        assertThat(r.confidence()).isEqualTo(RecommendationConfidence.MEDIUM);
    }

    // ---- easy / long / limited / cross ----------------------------------------------------------

    @Test
    void normalRestedDayGivesEasyWithoutSelectingTheFirstCandidateBlindly() {
        WorkoutRecommendation r = WorkoutRecommendationPolicy.recommend(
                regular(null, 2, 0, 2, LoadTrend.STABLE, RESTED));

        assertThat(r.recommendedIntent()).isEqualTo(EASY);
        assertThat(r.durationMinMinutes()).isEqualTo(30);
        assertThat(r.durationMaxMinutes()).isEqualTo(60);
        assertThat(r.intensityClass()).isEqualTo(IntensityClass.EASY);
        assertThat(r.confidence()).isEqualTo(RecommendationConfidence.MEDIUM);
        assertThat(r.dataSufficiency()).isEqualTo(DataSufficiency.HIGH);
        assertThat(r.reasons()).containsExactly(WorkoutRecommendationReason.RECENT_REST,
                WorkoutRecommendationReason.LOAD_TREND_STABLE, WorkoutRecommendationReason.QUALITY_HISTORY_UNAVAILABLE,
                WorkoutRecommendationReason.DEFAULT_EASY);
    }

    @Test
    void recommendationDoesNotDependOnCandidateOrder() {
        // EASY is not first here; the reversed list must give the same intent
        List<CandidateTrainingType> reversed = List.of(CROSS_TRAINING, LONG, QUALITY, EASY);
        assertThat(WorkoutRecommendationPolicy.recommend(regular(null, 2, 0, 2, LoadTrend.STABLE, reversed)).recommendedIntent())
                .isEqualTo(EASY);
        assertThat(WorkoutRecommendationPolicy.recommend(regular(1, 1, 0, 1, LoadTrend.STABLE, List.of(EASY, RECOVERY, REST))).recommendedIntent())
                .isEqualTo(RECOVERY);
    }

    @Test
    void longRunDueGivesLong() {
        WorkoutRecommendation r = WorkoutRecommendationPolicy.recommend(
                regular(7, 2, 0, 2, LoadTrend.STABLE, RESTED));

        assertThat(r.recommendedIntent()).isEqualTo(LONG);
        assertThat(r.durationMinMinutes()).isEqualTo(75);
        assertThat(r.durationMaxMinutes()).isEqualTo(120);
        assertThat(r.intensityClass()).isEqualTo(IntensityClass.EASY);
        assertThat(r.reasons()).contains(WorkoutRecommendationReason.LONG_RUN_DUE);
        assertThat(r.reasons()).doesNotContain(WorkoutRecommendationReason.DEFAULT_EASY);
    }

    @Test
    void longRunDueBoundaryIsSixDays() {
        assertThat(WorkoutRecommendationPolicy.recommend(regular(6, 2, 0, 2, LoadTrend.STABLE, RESTED)).recommendedIntent()).isEqualTo(LONG);
        assertThat(WorkoutRecommendationPolicy.recommend(regular(5, 2, 0, 2, LoadTrend.STABLE, RESTED)).recommendedIntent()).isEqualTo(EASY);
    }

    @Test
    void longIsHeldBackWhenAnyDueConditionFails() {
        // increasing load
        assertThat(WorkoutRecommendationPolicy.recommend(regular(8, 2, 0, 2, LoadTrend.INCREASING, RESTED)).recommendedIntent()).isEqualTo(EASY);
        // unknown trend -> data sufficiency is not HIGH
        assertThat(WorkoutRecommendationPolicy.recommend(regular(8, 2, 0, 2, LoadTrend.UNKNOWN, RESTED)).recommendedIntent()).isEqualTo(EASY);
        // no running in the last 3 days
        assertThat(WorkoutRecommendationPolicy.recommend(regular(8, 4, 0, 4, LoadTrend.STABLE, RESTED)).recommendedIntent()).isEqualTo(EASY);
        // no known previous long run: no long-run habit, LONG is not chosen
        assertThat(WorkoutRecommendationPolicy.recommend(regular(null, 2, 0, 2, LoadTrend.STABLE, RESTED)).recommendedIntent()).isEqualTo(EASY);
        // sparse history
        assertThat(WorkoutRecommendationPolicy.recommend(ctx(8, 2, 0, 2, LoadTrend.STABLE, 4, RESTED)).recommendedIntent()).isEqualTo(EASY);
        // too many consecutive active days (LONG plus 3 active days)
        assertThat(WorkoutRecommendationPolicy.recommend(regular(8, 0, 3, 0, LoadTrend.STABLE, List.of(REST, RECOVERY, EASY, LONG))).recommendedIntent()).isEqualTo(RECOVERY);
    }

    @Test
    void decreasingLoadKeepsLongEligible() {
        assertThat(WorkoutRecommendationPolicy.recommend(regular(9, 2, 0, 2, LoadTrend.DECREASING, RESTED)).recommendedIntent()).isEqualTo(LONG);
    }

    @Test
    void limitedHistoryGivesEasyWithLowConfidenceAndSufficiency() {
        WorkoutRecommendation r = WorkoutRecommendationPolicy.recommend(
                ctx(null, null, 0, 28, LoadTrend.UNKNOWN, 0, LIMITED,
                        DecisionReason.LIMITED_HISTORY, DecisionReason.NO_RECENT_RUNNING, DecisionReason.REST_DAY_RECENT));

        assertThat(r.recommendedIntent()).isEqualTo(EASY);
        assertThat(r.confidence()).isEqualTo(RecommendationConfidence.LOW);
        assertThat(r.dataSufficiency()).isEqualTo(DataSufficiency.LOW);
        assertThat(r.reasons()).contains(WorkoutRecommendationReason.LIMITED_HISTORY, WorkoutRecommendationReason.NO_RECENT_RUNNING);
        assertThat(r.reasons()).doesNotContain(WorkoutRecommendationReason.DEFAULT_EASY);
    }

    @Test
    void cyclingOnlyHistoryDefaultsToEasy() {
        WorkoutRecommendation r = WorkoutRecommendationPolicy.recommend(
                ctx(null, null, 0, 1, LoadTrend.STABLE, 8, RESTED,
                        DecisionReason.NO_RECENT_RUNNING, DecisionReason.RECENT_CYCLING));

        assertThat(r.recommendedIntent()).isEqualTo(EASY);
        assertThat(r.reasons()).contains(WorkoutRecommendationReason.NO_RECENT_RUNNING, WorkoutRecommendationReason.RECENT_CYCLING);
    }

    @Test
    void crossTrainingIsTheFallbackWhenEasyIsNotACandidate() {
        WorkoutRecommendation r = WorkoutRecommendationPolicy.recommend(
                regular(null, 2, 0, 2, LoadTrend.STABLE, List.of(CROSS_TRAINING, QUALITY)));

        assertThat(r.recommendedIntent()).isEqualTo(CROSS_TRAINING);
        assertThat(r.durationMinMinutes()).isEqualTo(30);
        assertThat(r.durationMaxMinutes()).isEqualTo(60);
        assertThat(r.intensityClass()).isEqualTo(IntensityClass.EASY);
        assertThat(r.confidence()).isEqualTo(RecommendationConfidence.LOW);
    }

    // ---- QUALITY -----------------------------------------------------------------------------------

    @Test
    void qualityIsNeverSelectedAndNoIntentOutsideTheCandidatesIsInvented() {
        // QUALITY-only (and other unselectable) candidate lists are invariant violations: no silent fallback
        for (List<CandidateTrainingType> unselectable : List.of(List.of(QUALITY), List.of(QUALITY, CandidateTrainingType.LONG))) {
            assertThatThrownBy(() -> WorkoutRecommendationPolicy.recommend(regular(null, 2, 0, 2, LoadTrend.STABLE, unselectable)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No selectable workout intent");
        }
        // the conservative fallback still stays inside the candidates
        assertThat(WorkoutRecommendationPolicy.recommend(regular(null, 2, 0, 2, LoadTrend.STABLE, List.of(QUALITY, REST))).recommendedIntent()).isEqualTo(REST);
        assertThat(WorkoutRecommendationPolicy.recommend(regular(null, 2, 0, 2, LoadTrend.STABLE, List.of(QUALITY, RECOVERY))).recommendedIntent()).isEqualTo(RECOVERY);

        WorkoutRecommendation rested = WorkoutRecommendationPolicy.recommend(regular(null, 2, 0, 2, LoadTrend.STABLE, RESTED));
        assertThat(rested.recommendedIntent()).isNotEqualTo(QUALITY);
        assertThat(rested.reasons()).contains(WorkoutRecommendationReason.QUALITY_HISTORY_UNAVAILABLE);
    }

    @Test
    void qualityReasonAppearsOnlyWhenQualityWasACandidate() {
        assertThat(WorkoutRecommendationPolicy.recommend(regular(1, 1, 0, 1, LoadTrend.STABLE, RESTRICTED)).reasons())
                .doesNotContain(WorkoutRecommendationReason.QUALITY_HISTORY_UNAVAILABLE);
    }

    // ---- data sufficiency -----------------------------------------------------------------------

    @Test
    void dataSufficiencyThresholds() {
        assertThat(WorkoutRecommendationPolicy.sufficiency(ctx(null, 1, 0, 1, LoadTrend.STABLE, 2, RESTED), false)).isEqualTo(DataSufficiency.LOW);
        assertThat(WorkoutRecommendationPolicy.sufficiency(ctx(null, 1, 0, 1, LoadTrend.STABLE, 3, RESTED), false)).isEqualTo(DataSufficiency.MEDIUM);
        assertThat(WorkoutRecommendationPolicy.sufficiency(ctx(null, 1, 0, 1, LoadTrend.STABLE, 5, RESTED), false)).isEqualTo(DataSufficiency.MEDIUM);
        assertThat(WorkoutRecommendationPolicy.sufficiency(ctx(null, 1, 0, 1, LoadTrend.UNKNOWN, 8, RESTED), false)).isEqualTo(DataSufficiency.MEDIUM);
        assertThat(WorkoutRecommendationPolicy.sufficiency(ctx(null, 1, 0, 1, LoadTrend.STABLE, 6, RESTED), false)).isEqualTo(DataSufficiency.HIGH);
        assertThat(WorkoutRecommendationPolicy.sufficiency(ctx(null, 1, 0, 1, LoadTrend.STABLE, 8, RESTED), true)).isEqualTo(DataSufficiency.LOW);
    }

    // ---- mapping, membership, determinism, vocabulary ---------------------------------------------

    @Test
    void durationAndIntensityMappingPerIntent() {
        assertThat(mapping(REST)).containsExactly(0, 0, IntensityClass.NONE);
        assertThat(mapping(RECOVERY)).containsExactly(20, 40, IntensityClass.VERY_EASY);
        assertThat(mapping(EASY)).containsExactly(30, 60, IntensityClass.EASY);
        assertThat(mapping(QUALITY)).containsExactly(30, 70, IntensityClass.HARD);
        assertThat(mapping(LONG)).containsExactly(75, 120, IntensityClass.EASY);
        assertThat(mapping(CROSS_TRAINING)).containsExactly(30, 60, IntensityClass.EASY);
    }

    private static Object[] mapping(CandidateTrainingType intent) {
        return new Object[]{WorkoutRecommendationPolicy.minMinutes(intent), WorkoutRecommendationPolicy.maxMinutes(intent),
                WorkoutRecommendationPolicy.intensity(intent)};
    }

    @Test
    void everyGeneratedContextGivesOneMemberOfItsCandidatesDeterministically() {
        List<List<CandidateTrainingType>> sets = List.of(RESTRICTED, RESTED, ACTIVE_TODAY, LIMITED);
        List<Integer> sinceLongValues = Arrays.asList(null, 0, 1, 2, 6, 10);
        int checked = 0;
        for (List<CandidateTrainingType> candidates : sets) {
            for (Integer sinceLong : sinceLongValues) {
                for (int active = 0; active <= 5; active++) {
                    for (LoadTrend trend : LoadTrend.values()) {
                        for (int activeDays14 : new int[]{0, 2, 4, 8}) {
                            TrainingDecisionContext c = ctx(sinceLong, active == 0 ? 3 : 0, active, active == 0 ? 2 : 0,
                                    trend, activeDays14, candidates);
                            WorkoutRecommendation a = WorkoutRecommendationPolicy.recommend(c);
                            WorkoutRecommendation b = WorkoutRecommendationPolicy.recommend(c);

                            assertThat(a).isEqualTo(b);
                            assertThat(a.recommendedIntent()).isIn(candidates);
                            assertThat(a.recommendedIntent()).isNotEqualTo(QUALITY);
                            assertThat(a.decisionContext()).isSameAs(c);
                            assertThat(a.reasons()).doesNotHaveDuplicates()
                                    .isSortedAccordingTo(Comparator.comparingInt(WorkoutRecommendationReason::ordinal));
                            assertThat(a.summary()).isNotBlank();
                            assertThat(a.summary().toLowerCase())
                                    .doesNotContain("injur", "overtrain", "unsafe", "medical", "recovered", "readiness");
                            checked++;
                        }
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(4 * 6 * 6 * 4 * 4);
    }
}

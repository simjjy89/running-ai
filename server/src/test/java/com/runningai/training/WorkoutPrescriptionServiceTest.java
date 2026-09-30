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
import static com.runningai.training.CandidateTrainingType.RECOVERY;
import static com.runningai.training.CandidateTrainingType.REST;
import static org.assertj.core.api.Assertions.assertThat;

/** Prescription over the real recommendation flow; asOf = 2026-09-30 (Asia/Seoul), fixed clock 01:00 that day. */
@SpringBootTest
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class WorkoutPrescriptionServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);
    private static final AtomicInteger IDS = new AtomicInteger();

    @Autowired
    private WorkoutPrescriptionService service;

    @Autowired
    private WorkoutRecommendationService recommendationService;

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
        Instant start = AS_OF.minusDays(daysBeforeAsOf).atTime(6, 0).atZone(SEOUL).toInstant();
        activityRepository.save(new Activity(athleteId, ExternalSource.MANUAL, "wp-" + IDS.incrementAndGet(),
                ActivityType.RUN, start, minutes * 60, minutes * 100.0, null, null));
    }

    private void regularHistory(int previousLongMinutes) {
        runOn(9, 30);
        runOn(11, 30);
        runOn(13, 30);
        runOn(7, previousLongMinutes);
        int each = (previousLongMinutes + 90) / 3;
        runOn(2, each);
        runOn(4, each);
        runOn(6, each);
    }

    private static int sum(WorkoutPrescription p) {
        return p.segments().stream().mapToInt(WorkoutSegment::durationMinutes).sum();
    }

    @Test
    void easyForAnEmptyHistory() {
        WorkoutPrescription p = service.prescribe(AS_OF);

        assertThat(p.asOfDate()).isEqualTo(AS_OF);
        assertThat(p.intent()).isEqualTo(EASY);
        assertThat(p.totalDurationMinutes()).isEqualTo(45);
        assertThat(sum(p)).isEqualTo(45);
    }

    @Test
    void recoveryAfterALongRun() {
        runOn(1, 120);

        WorkoutPrescription p = service.prescribe(AS_OF);

        assertThat(p.intent()).isEqualTo(RECOVERY);
        assertThat(p.totalDurationMinutes()).isEqualTo(30);
        assertThat(p.segments()).extracting(WorkoutSegment::durationMinutes).containsExactly(5, 20, 5);
    }

    @Test
    void restAfterALongRunAndManyActiveDays() {
        runOn(4, 30);
        runOn(3, 30);
        runOn(2, 30);
        runOn(1, 120);
        runOn(0, 30);

        WorkoutPrescription p = service.prescribe(AS_OF);

        assertThat(p.intent()).isEqualTo(REST);
        assertThat(p.totalDurationMinutes()).isZero();
        assertThat(p.segments()).singleElement().satisfies(s -> {
            assertThat(s.type()).isEqualTo(SegmentType.REST);
            assertThat(s.intensityClass()).isEqualTo(IntensityClass.NONE);
        });
    }

    @Test
    void longWhenARunIsDue() {
        regularHistory(120);

        WorkoutPrescription p = service.prescribe(AS_OF);

        assertThat(p.intent()).isEqualTo(LONG);
        assertThat(p.totalDurationMinutes()).isEqualTo(90);
        assertThat(p.segments()).extracting(WorkoutSegment::durationMinutes).containsExactly(10, 70, 10);
    }

    @Test
    void intentAndRangeComeFromTheNestedRecommendationUnchanged() {
        regularHistory(120);
        runOn(1, 100);

        for (int back = 0; back <= 25; back++) {
            LocalDate day = AS_OF.minusDays(back);
            WorkoutPrescription p = service.prescribe(day);
            WorkoutRecommendation r = recommendationService.recommend(day);

            assertThat(p.recommendation()).isEqualTo(r);
            assertThat(p.intent()).isEqualTo(r.recommendedIntent());
            assertThat(p.asOfDate()).isEqualTo(r.asOfDate());
            assertThat(p.totalDurationMinutes()).isBetween(r.durationMinMinutes(), r.durationMaxMinutes());
            assertThat(sum(p)).isEqualTo(p.totalDurationMinutes());
        }
    }

    @Test
    void sameInputGivesTheSamePrescription() {
        regularHistory(120);
        assertThat(service.prescribe(AS_OF)).isEqualTo(service.prescribe(AS_OF));
    }

    @Test
    void historicalDateIgnoresLaterActivities() {
        runOn(5, 30);
        runOn(1, 120);      // a long run after the queried date
        runOn(0, 30);

        WorkoutPrescription p = service.prescribe(LocalDate.of(2026, 9, 26));

        assertThat(p.asOfDate()).isEqualTo(LocalDate.of(2026, 9, 26));
        assertThat(p.recommendation().decisionContext().lastLongRunDate()).isNull();
        assertThat(p.recommendation().reasons()).doesNotContain(WorkoutRecommendationReason.RECENT_LONG_RUN);
    }

    @Test
    void todayIsTheAthleteLocalDate() {
        assertThat(service.today()).isEqualTo(AS_OF);
        assertThat(service.prescribe(service.today()).asOfDate()).isEqualTo(AS_OF);
    }
}

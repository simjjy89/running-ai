package com.runningai.training;

import com.runningai.athlete.AthleteIntensityProfileRequest;
import com.runningai.athlete.AthleteIntensityProfileService;
import com.runningai.athlete.AthleteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring over the real {@link WorkoutPrescriptionService} and {@link AthleteIntensityProfileService};
 * asOf = 2026-09-30 (Asia/Seoul), fixed clock 01:00 that day. All LTHR/pace values are synthetic.
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
@ActiveProfiles("test")
@Transactional
class WorkoutIntensityTargetServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    @Autowired
    private WorkoutIntensityTargetService service;

    @Autowired
    private AthleteIntensityProfileService profileService;

    @Autowired
    private AthleteService athleteService;

    @BeforeEach
    void setUp() {
        athleteService.getDefaultAthlete();
    }

    @Test
    void todayIsTheAthleteLocalDate() {
        assertThat(service.today()).isEqualTo(AS_OF);
        assertThat(service.targetedPrescribe(service.today()).asOfDate()).isEqualTo(AS_OF);
    }

    @Test
    void noProfileFallsBackToQualitative() {
        TargetedWorkoutPrescription t = service.targetedPrescribe(AS_OF);

        assertThat(t.targetAvailability()).isEqualTo(TargetAvailability.QUALITATIVE_ONLY);
        assertThat(t.profile().initialized()).isFalse();
    }

    @Test
    void profileUpdateIsReflectedOnTheVeryNextCall() {
        TargetedWorkoutPrescription before = service.targetedPrescribe(AS_OF);
        assertThat(before.targetAvailability()).isEqualTo(TargetAvailability.QUALITATIVE_ONLY);

        profileService.replaceDefaultProfile(new AthleteIntensityProfileRequest(160, 300));

        TargetedWorkoutPrescription after = service.targetedPrescribe(AS_OF);
        assertThat(after.targetAvailability()).isEqualTo(TargetAvailability.FULL);
        assertThat(after.profile().lactateThresholdHeartRateBpm()).isEqualTo(160);
    }

    @Test
    void historicalDateStillUsesTheCurrentProfileNotAPastOne() {
        LocalDate past = AS_OF.minusDays(10);
        TargetedWorkoutPrescription beforeProfile = service.targetedPrescribe(past);
        assertThat(beforeProfile.targetAvailability()).isEqualTo(TargetAvailability.QUALITATIVE_ONLY);

        profileService.replaceDefaultProfile(new AthleteIntensityProfileRequest(160, 300));

        TargetedWorkoutPrescription afterProfile = service.targetedPrescribe(past);
        assertThat(afterProfile.asOfDate()).isEqualTo(past);
        assertThat(afterProfile.targetAvailability()).isEqualTo(TargetAvailability.FULL);
    }

    @Test
    void underlyingPrescriptionIsUnchanged() {
        TargetedWorkoutPrescription t = service.targetedPrescribe(AS_OF);
        assertThat(t.prescription().asOfDate()).isEqualTo(t.asOfDate());
        assertThat(t.prescription().intent()).isEqualTo(t.intent());
        assertThat(t.prescription().totalDurationMinutes()).isEqualTo(t.totalDurationMinutes());
    }
}

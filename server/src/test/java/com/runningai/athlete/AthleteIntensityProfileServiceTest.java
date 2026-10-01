package com.runningai.athlete;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** All LTHR/pace values below are synthetic, not real athlete data. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AthleteIntensityProfileServiceTest {

    @Autowired
    private AthleteIntensityProfileService service;

    @Autowired
    private AthleteIntensityProfileRepository repository;

    @Autowired
    private AthleteService athleteService;

    private Long athleteId;

    @BeforeEach
    void setUp() {
        athleteId = athleteService.getDefaultAthlete().getId();
    }

    @Test
    void emptyBeforeAnyPut() {
        assertThat(service.getDefaultProfile()).isEqualTo(AthleteIntensityProfileResponse.empty());
    }

    @Test
    void putCreatesTheProfile() {
        AthleteIntensityProfileResponse result = service.replaceDefaultProfile(
                new AthleteIntensityProfileRequest(170, 300));

        assertThat(result.initialized()).isTrue();
        assertThat(result.lactateThresholdHeartRateBpm()).isEqualTo(170);
        assertThat(result.lactateThresholdPaceSecondsPerKm()).isEqualTo(300);
        assertThat(service.getDefaultProfile()).isEqualTo(result);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void secondPutReplacesInPlaceRatherThanCreatingASecondRow() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(170, 300));
        Long rowId = repository.findByAthleteId(athleteId).orElseThrow().getId();

        AthleteIntensityProfileResponse second = service.replaceDefaultProfile(
                new AthleteIntensityProfileRequest(175, 290));

        assertThat(second.lactateThresholdHeartRateBpm()).isEqualTo(175);
        assertThat(second.lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByAthleteId(athleteId).orElseThrow().getId()).isEqualTo(rowId);
    }

    @Test
    void putWithANullFieldClearsThatMetric() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(170, 300));

        AthleteIntensityProfileResponse cleared = service.replaceDefaultProfile(
                new AthleteIntensityProfileRequest(null, 300));

        assertThat(cleared.lactateThresholdHeartRateBpm()).isNull();
        assertThat(cleared.lactateThresholdPaceSecondsPerKm()).isEqualTo(300);
    }

    @Test
    void partialProfilesAreAccepted() {
        AthleteIntensityProfileResponse paceOnly = service.replaceDefaultProfile(
                new AthleteIntensityProfileRequest(null, 300));
        assertThat(paceOnly.initialized()).isTrue();
        assertThat(paceOnly.lactateThresholdHeartRateBpm()).isNull();
        assertThat(paceOnly.lactateThresholdPaceSecondsPerKm()).isEqualTo(300);

        AthleteIntensityProfileResponse hrOnly = service.replaceDefaultProfile(
                new AthleteIntensityProfileRequest(170, null));
        assertThat(hrOnly.lactateThresholdHeartRateBpm()).isEqualTo(170);
        assertThat(hrOnly.lactateThresholdPaceSecondsPerKm()).isNull();
    }

    @Test
    void nonPositiveLthrIsRejected() {
        assertThatThrownBy(() -> service.replaceDefaultProfile(new AthleteIntensityProfileRequest(0, 300)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveThresholdPaceIsRejected() {
        assertThatThrownBy(() -> service.replaceDefaultProfile(new AthleteIntensityProfileRequest(170, -1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Garmin sync merge (Phase 6D) ----------------------------------------------------------------------------

    @Test
    void mergeOnAnEmptyProfileInsertsBothMetrics() {
        GarminProfileMergeResult result = service.mergeGarminSnapshot(180, 290);

        assertThat(result.updated()).isTrue();
        assertThat(result.heartRateChanged()).isTrue();
        assertThat(result.paceChanged()).isTrue();
        assertThat(result.profile().lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(result.profile().lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void mergeUpdatesBothMetricsWhenBothChange() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(170, 300));

        GarminProfileMergeResult result = service.mergeGarminSnapshot(180, 290);

        assertThat(result.updated()).isTrue();
        assertThat(result.heartRateChanged()).isTrue();
        assertThat(result.paceChanged()).isTrue();
        assertThat(result.profile().lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(result.profile().lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void mergeWithIdenticalValuesIsNotAnUpdate() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(180, 290));

        GarminProfileMergeResult result = service.mergeGarminSnapshot(180, 290);

        assertThat(result.updated()).isFalse();
        assertThat(result.heartRateChanged()).isFalse();
        assertThat(result.paceChanged()).isFalse();
        assertThat(result.profile().lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(result.profile().lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
    }

    @Test
    void heartRateOnlyFromGarminPreservesExistingPace() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(170, 300));

        GarminProfileMergeResult result = service.mergeGarminSnapshot(180, null);

        assertThat(result.updated()).isTrue();
        assertThat(result.heartRateChanged()).isTrue();
        assertThat(result.paceChanged()).isFalse();
        assertThat(result.profile().lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(result.profile().lactateThresholdPaceSecondsPerKm()).isEqualTo(300);
    }

    @Test
    void paceOnlyFromGarminPreservesExistingHeartRate() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(170, 300));

        GarminProfileMergeResult result = service.mergeGarminSnapshot(null, 290);

        assertThat(result.updated()).isTrue();
        assertThat(result.heartRateChanged()).isFalse();
        assertThat(result.paceChanged()).isTrue();
        assertThat(result.profile().lactateThresholdHeartRateBpm()).isEqualTo(170);
        assertThat(result.profile().lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
    }

    @Test
    void bothMetricsMissingFromGarminMeansNoDatabaseChange() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(170, 300));
        Long rowId = repository.findByAthleteId(athleteId).orElseThrow().getId();

        GarminProfileMergeResult result = service.mergeGarminSnapshot(null, null);

        assertThat(result.updated()).isFalse();
        assertThat(result.profile().lactateThresholdHeartRateBpm()).isEqualTo(170);
        assertThat(result.profile().lactateThresholdPaceSecondsPerKm()).isEqualTo(300);
        assertThat(repository.findByAthleteId(athleteId).orElseThrow().getId()).isEqualTo(rowId);
    }

    @Test
    void bothMetricsMissingOnAnEmptyProfileStaysEmptyAndWritesNoRow() {
        GarminProfileMergeResult result = service.mergeGarminSnapshot(null, null);

        assertThat(result.updated()).isFalse();
        assertThat(result.profile()).isEqualTo(AthleteIntensityProfileResponse.empty());
        assertThat(repository.count()).isZero();
    }

    @Test
    void manualProfileSurvivesAsFallbackUntilGarminProvidesAValue() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(180, 290));

        // simulates a Garmin fetch where the connector returned nothing usable for either metric
        GarminProfileMergeResult result = service.mergeGarminSnapshot(null, null);

        assertThat(result.profile()).isEqualTo(service.getDefaultProfile());
        assertThat(service.getDefaultProfile().lactateThresholdHeartRateBpm()).isEqualTo(180);
        assertThat(service.getDefaultProfile().lactateThresholdPaceSecondsPerKm()).isEqualTo(290);
    }
}

package com.runningai.integration.garmin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests of the speed -> pace conversion derived in Phase 6D-0. */
class GarminSpeedConverterTest {

    @Test
    void realLiveValueConvertsToTheKnownLactateThresholdPace() {
        // Live-probed raw value (Phase 6D-0); the owner's known real LT pace is 290 sec/km (4:50/km).
        Integer secondsPerKm = GarminSpeedConverter.rawSpeedToSecondsPerKilometer(0.34444348);

        assertThat(secondsPerKm).isEqualTo(290);
    }

    @Test
    void nullIsPassedThrough() {
        assertThat(GarminSpeedConverter.rawSpeedToSecondsPerKilometer(null)).isNull();
    }

    @Test
    void zeroIsRejectedToAvoidDivideByZero() {
        assertThat(GarminSpeedConverter.rawSpeedToSecondsPerKilometer(0.0)).isNull();
    }

    @Test
    void negativeSpeedIsRejected() {
        assertThat(GarminSpeedConverter.rawSpeedToSecondsPerKilometer(-1.0)).isNull();
    }

    @Test
    void nanIsRejected() {
        assertThat(GarminSpeedConverter.rawSpeedToSecondsPerKilometer(Double.NaN)).isNull();
    }

    @Test
    void infiniteIsRejected() {
        assertThat(GarminSpeedConverter.rawSpeedToSecondsPerKilometer(Double.POSITIVE_INFINITY)).isNull();
    }

    @Test
    void extremelySmallPositiveSpeedDoesNotOverflowIntoAnException() {
        // 1e-300 would overflow to a huge, meaningless pace; the converter must reject it, not throw.
        assertThat(GarminSpeedConverter.rawSpeedToSecondsPerKilometer(1e-300)).isNull();
    }

    @Test
    void fasterSpeedGivesAShorterPace() {
        Integer slower = GarminSpeedConverter.rawSpeedToSecondsPerKilometer(0.3);
        Integer faster = GarminSpeedConverter.rawSpeedToSecondsPerKilometer(0.4);

        assertThat(faster).isLessThan(slower);
    }
}

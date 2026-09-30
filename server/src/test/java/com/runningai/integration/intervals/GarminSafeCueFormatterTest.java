package com.runningai.integration.intervals;

import com.runningai.training.TreadmillTarget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GarminSafeCueFormatterTest {

    @Test
    void nullTreadmillTargetGivesNoCue() {
        assertThat(GarminSafeCueFormatter.cue(null)).isNull();
    }

    @Test
    void speedAndInclineBothPresent() {
        TreadmillTarget target = new TreadmillTarget(8.3, 9.6, 0.0, 0.5);

        assertThat(GarminSafeCueFormatter.cue(target)).isEqualTo("8.3-9.6kph Incline0-0.5pct");
    }

    @Test
    void inclineOnlyWhenSpeedIsAbsent() {
        TreadmillTarget target = new TreadmillTarget(null, null, 0.5, 1.0);

        assertThat(GarminSafeCueFormatter.cue(target)).isEqualTo("Incline0.5-1pct");
    }

    @Test
    void singleValueSpeedRendersWithoutADash() {
        TreadmillTarget target = new TreadmillTarget(12.0, 12.0, 0.5, 1.0);

        assertThat(GarminSafeCueFormatter.cue(target)).isEqualTo("12.0kph Incline0.5-1pct");
    }

    @Test
    void singleValueInclineRendersWithoutADash() {
        TreadmillTarget target = new TreadmillTarget(10.0, 10.0, 1.0, 1.0);

        assertThat(GarminSafeCueFormatter.cue(target)).isEqualTo("10.0kph Incline1pct");
    }

    @Test
    void speedAlwaysKeepsOneDecimalPlaceEvenForAWholeNumber() {
        TreadmillTarget target = new TreadmillTarget(12.0, 12.0, 0.0, 0.0);

        assertThat(GarminSafeCueFormatter.cue(target)).isEqualTo("12.0kph Incline0pct");
    }

    @Test
    void inclineTrimsTrailingZerosButKeepsNonZeroDecimals() {
        assertThat(GarminSafeCueFormatter.cue(new TreadmillTarget(null, null, 0.0, 0.0))).isEqualTo("Incline0pct");
        assertThat(GarminSafeCueFormatter.cue(new TreadmillTarget(null, null, 1.0, 1.0))).isEqualTo("Incline1pct");
        assertThat(GarminSafeCueFormatter.cue(new TreadmillTarget(null, null, 0.5, 0.5))).isEqualTo("Incline0.5pct");
    }

    @Test
    void rejectsNonFiniteOrNegativeIncline() {
        assertThatThrownBy(() -> GarminSafeCueFormatter.cue(new TreadmillTarget(null, null, -0.5, 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GarminSafeCueFormatter.cue(new TreadmillTarget(null, null, Double.NaN, 1.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonFiniteOrNegativeSpeed() {
        assertThatThrownBy(() -> GarminSafeCueFormatter.cue(new TreadmillTarget(-1.0, 9.0, 0.0, 0.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GarminSafeCueFormatter.cue(new TreadmillTarget(8.0, Double.POSITIVE_INFINITY, 0.0, 0.5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void containsNoPercentSignAndNoRawUnitText() {
        String cue = GarminSafeCueFormatter.cue(new TreadmillTarget(8.3, 9.6, 0.0, 0.5));

        assertThat(cue).doesNotContain("%").doesNotContain("km/h").doesNotContain("경사");
    }
}

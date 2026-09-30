package com.runningai.training;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure conversion rules; all values below are synthetic, not real athlete data. */
class RunningIntensityTargetPolicyTest {

    private static final int THRESHOLD_PACE = 300;   // synthetic seconds/km
    private static final int LTHR = 160;              // synthetic bpm

    @Test
    void veryEasyPaceIsOneHundredTwentyFiveToOneHundredFortyFivePercentOfThreshold() {
        PaceTarget target = RunningIntensityTargetPolicy.paceTarget(IntensityClass.VERY_EASY, THRESHOLD_PACE);

        assertThat(target.fastSecondsPerKm()).isEqualTo(375);   // 300 * 1.25
        assertThat(target.slowSecondsPerKm()).isEqualTo(435);   // 300 * 1.45
    }

    @Test
    void easyPaceIsOneHundredFifteenToOneHundredThirtyPercentOfThreshold() {
        PaceTarget target = RunningIntensityTargetPolicy.paceTarget(IntensityClass.EASY, THRESHOLD_PACE);

        assertThat(target.fastSecondsPerKm()).isEqualTo(345);   // 300 * 1.15
        assertThat(target.slowSecondsPerKm()).isEqualTo(390);   // 300 * 1.30
    }

    @Test
    void paceTargetIsNullWithoutAThresholdPace() {
        assertThat(RunningIntensityTargetPolicy.paceTarget(IntensityClass.VERY_EASY, null)).isNull();
    }

    @Test
    void paceTargetIsNullForAnUnsupportedIntensityClass() {
        assertThat(RunningIntensityTargetPolicy.paceTarget(IntensityClass.MODERATE, THRESHOLD_PACE)).isNull();
        assertThat(RunningIntensityTargetPolicy.paceTarget(IntensityClass.HARD, THRESHOLD_PACE)).isNull();
        assertThat(RunningIntensityTargetPolicy.paceTarget(IntensityClass.NONE, THRESHOLD_PACE)).isNull();
    }

    @Test
    void veryEasyHeartRateIsSixtyFiveToSeventyEightPercentLthr() {
        HeartRateTarget target = RunningIntensityTargetPolicy.heartRateTarget(IntensityClass.VERY_EASY, LTHR);

        assertThat(target.minPercentLthr()).isEqualTo(65);
        assertThat(target.maxPercentLthr()).isEqualTo(78);
        assertThat(target.minBpm()).isEqualTo(104);   // round(160 * 0.65)
        assertThat(target.maxBpm()).isEqualTo(125);   // round(160 * 0.78) = 124.8 -> 125
    }

    @Test
    void easyHeartRateIsSeventyFiveToEightyFivePercentLthr() {
        HeartRateTarget target = RunningIntensityTargetPolicy.heartRateTarget(IntensityClass.EASY, LTHR);

        assertThat(target.minPercentLthr()).isEqualTo(75);
        assertThat(target.maxPercentLthr()).isEqualTo(85);
        assertThat(target.minBpm()).isEqualTo(120);   // round(160 * 0.75)
        assertThat(target.maxBpm()).isEqualTo(136);   // round(160 * 0.85)
        assertThat(target.minBpm()).isLessThanOrEqualTo(target.maxBpm());
    }

    @Test
    void heartRateTargetIsNullWithoutLthr() {
        assertThat(RunningIntensityTargetPolicy.heartRateTarget(IntensityClass.VERY_EASY, null)).isNull();
    }

    @Test
    void heartRateTargetIsNullForAnUnsupportedIntensityClass() {
        assertThat(RunningIntensityTargetPolicy.heartRateTarget(IntensityClass.HARD, LTHR)).isNull();
    }

    @Test
    void treadmillSpeedIsNullWithoutAPaceTarget() {
        TreadmillTarget target = RunningIntensityTargetPolicy.treadmillTarget(SegmentType.MAIN, null);

        assertThat(target.minSpeedKph()).isNull();
        assertThat(target.maxSpeedKph()).isNull();
        assertThat(target.minInclinePercent()).isEqualTo(0.5);
        assertThat(target.maxInclinePercent()).isEqualTo(1.0);
    }

    @Test
    void treadmillSpeedIsDerivedFromPaceInTheOppositeDirection() {
        PaceTarget pace = RunningIntensityTargetPolicy.paceTarget(IntensityClass.VERY_EASY, THRESHOLD_PACE); // 375..435
        TreadmillTarget target = RunningIntensityTargetPolicy.treadmillTarget(SegmentType.MAIN, pace);

        assertThat(target.minSpeedKph()).isEqualTo(8.3);   // 3600 / 435 = 8.2758... -> 8.3
        assertThat(target.maxSpeedKph()).isEqualTo(9.6);   // 3600 / 375 = 9.6
        assertThat(target.minSpeedKph()).isLessThanOrEqualTo(target.maxSpeedKph());
    }

    @Test
    void speedRoundingIsHalfUpToOneDecimal() {
        // 3600 / 320 = 11.25 exactly -> rounds up to 11.3
        TreadmillTarget roundsUp = RunningIntensityTargetPolicy.treadmillTarget(SegmentType.MAIN, new PaceTarget(320, 320));
        assertThat(roundsUp.maxSpeedKph()).isEqualTo(11.3);

        // 3600 / 336 = 10.714285... -> rounds down to 10.7
        TreadmillTarget roundsDown = RunningIntensityTargetPolicy.treadmillTarget(SegmentType.MAIN, new PaceTarget(336, 336));
        assertThat(roundsDown.maxSpeedKph()).isEqualTo(10.7);
    }

    @Test
    void inclineDefaultsByPositionInTheWorkout() {
        assertThat(RunningIntensityTargetPolicy.treadmillTarget(SegmentType.WARM_UP, null).minInclinePercent()).isEqualTo(0.0);
        assertThat(RunningIntensityTargetPolicy.treadmillTarget(SegmentType.WARM_UP, null).maxInclinePercent()).isEqualTo(0.5);
        assertThat(RunningIntensityTargetPolicy.treadmillTarget(SegmentType.MAIN, null).minInclinePercent()).isEqualTo(0.5);
        assertThat(RunningIntensityTargetPolicy.treadmillTarget(SegmentType.MAIN, null).maxInclinePercent()).isEqualTo(1.0);
        assertThat(RunningIntensityTargetPolicy.treadmillTarget(SegmentType.COOL_DOWN, null).minInclinePercent()).isEqualTo(0.0);
        assertThat(RunningIntensityTargetPolicy.treadmillTarget(SegmentType.COOL_DOWN, null).maxInclinePercent()).isEqualTo(0.5);
    }

    @Test
    void treadmillTargetHasNoDefaultForARestSegment() {
        assertThatThrownBy(() -> RunningIntensityTargetPolicy.treadmillTarget(SegmentType.REST, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

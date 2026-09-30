package com.runningai.training;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Tunables of the training decision context. These are RunningAI heuristics and descriptive bands,
 * not physiological or safety limits.
 *
 * @param classification session classification tunables
 * @param decision       decision-context tunables
 */
@Validated
@ConfigurationProperties(prefix = "running-ai.training")
public record TrainingProperties(
        @DefaultValue Classification classification,
        @DefaultValue Decision decision
) {

    /** @param longRunMinDuration a single RUN / TREADMILL_RUN at least this long is labelled LONG */
    public record Classification(@DefaultValue("90m") Duration longRunMinDuration) {
        public Classification {
            if (longRunMinDuration == null || longRunMinDuration.isNegative() || longRunMinDuration.isZero()) {
                throw new IllegalArgumentException("running-ai.training.classification.long-run-min-duration must be positive");
            }
        }
    }

    /**
     * @param stableBandPercent load trend is STABLE within +/- this percent of weekly load change
     * @param patternDays       calendar days (ending on the as-of day) returned as recentPattern, 1..28
     */
    public record Decision(@DefaultValue("10") double stableBandPercent, @DefaultValue("14") int patternDays) {
        public Decision {
            if (stableBandPercent < 0) {
                throw new IllegalArgumentException("running-ai.training.decision.stable-band-percent must not be negative");
            }
            if (patternDays < 1 || patternDays > TrainingDecisionContextService.HISTORY_DAYS) {
                throw new IllegalArgumentException("running-ai.training.decision.pattern-days must be between 1 and "
                        + TrainingDecisionContextService.HISTORY_DAYS);
            }
        }
    }
}

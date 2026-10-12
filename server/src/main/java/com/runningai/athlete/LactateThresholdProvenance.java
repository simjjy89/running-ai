package com.runningai.athlete;

import java.time.Instant;

/**
 * What RunningAI can actually prove about where the current lactate-threshold values came from
 * (Phase 7B-4). Field semantics and null rules:
 *
 * <ul>
 *   <li>{@code sourceTracked} — {@code true} ONLY when the latest {@code applied=true} history row
 *       exists and its values equal the current profile exactly. A same-value observation row never
 *       makes this true (it proves freshness, not origin), and a profile written before history
 *       existed (or changed outside the two audited paths) stays {@code false}.</li>
 *   <li>{@code source} — {@code "MANUAL"} or {@code "GARMIN"} when {@code sourceTracked}, else
 *       {@code null} (never a guess).</li>
 *   <li>{@code lastAppliedChangeAt} — when the profile last actually changed through an audited
 *       path; {@code null} when no applied history exists. Consumers compare this with an
 *       analysis's {@code computedAt} to recognise LTHR-exposure numbers computed under an older
 *       threshold (analyses are never auto-recomputed).</li>
 *   <li>{@code lastGarminObservationCalendarDateRaw} / {@code lastGarminObservationFetchedAt} —
 *       the newest GARMIN event regardless of {@code applied}: Garmin's own measurement timestamp
 *       VERBATIM (undocumented zone, never converted) and RunningAI's UTC fetch time. {@code null}
 *       when Garmin has never been observed through the audited path.</li>
 * </ul>
 */
public record LactateThresholdProvenance(
        Integer lactateThresholdHeartRateBpm,
        Integer lactateThresholdPaceSecondsPerKm,
        boolean sourceTracked,
        String source,
        Instant lastAppliedChangeAt,
        String lastGarminObservationCalendarDateRaw,
        Instant lastGarminObservationFetchedAt
) {
}

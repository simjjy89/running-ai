package com.runningai.integration.garmin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/v1/garmin/recovery-sync/coverage}: day-by-day recovery coverage over a date range,
 * computed from stored rows only - no connector or Garmin call is made.
 * <p>
 * Facts only, in the spirit of the rest of the data layer: it reports what is stored and what the
 * last collection attempt found, and never rates a day, a metric or the athlete.
 *
 * @param statusCounts      how many days fall into each {@link GarminRecoveryCoverageStatus}, every status listed
 * @param datesWorthFetching the days a backfill could still improve, oldest first - deliberately
 *                          excluding days Garmin has already said it has no data for
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminRecoveryCoverageResponse(
        LocalDate startDate,
        LocalDate endDate,
        int totalDays,
        Map<GarminRecoveryCoverageStatus, Integer> statusCounts,
        List<LocalDate> datesWorthFetching,
        List<DayCoverage> days
) {

    static GarminRecoveryCoverageResponse of(LocalDate startDate, LocalDate endDate, List<DayCoverage> days) {
        Map<GarminRecoveryCoverageStatus, Integer> counts = new EnumMap<>(GarminRecoveryCoverageStatus.class);
        for (GarminRecoveryCoverageStatus status : GarminRecoveryCoverageStatus.values()) {
            counts.put(status, 0);
        }
        List<LocalDate> worthFetching = days.stream()
                .filter(day -> day.status().worthFetching())
                .map(DayCoverage::date)
                .toList();
        days.forEach(day -> counts.merge(day.status(), 1, Integer::sum));
        return new GarminRecoveryCoverageResponse(startDate, endDate, days.size(), counts, worthFetching,
                List.copyOf(days));
    }

    /**
     * One day.
     *
     * @param metrics              connector metric name to "is a value stored", in connector order
     * @param missingMetrics       the subset of {@code metrics} with no stored value
     * @param lastCollectionOutcome what the last recorded attempt found, or null when none is recorded
     *                             (a day collected before Phase 8A has no record, which is not the
     *                             same as never having been collected)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DayCoverage(
            LocalDate date,
            GarminRecoveryCoverageStatus status,
            Map<String, Boolean> metrics,
            List<String> missingMetrics,
            GarminRecoveryCollectionOutcome lastCollectionOutcome,
            Instant lastAttemptedAt,
            String lastFailureCode
    ) {
    }
}

package com.runningai.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    boolean existsByExternalSourceAndExternalId(ExternalSource externalSource, String externalId);

    Optional<Activity> findByExternalSourceAndExternalId(ExternalSource externalSource, String externalId);

    List<Activity> findAllByOrderByStartedAtDesc();

    /**
     * Activities of one athlete with {@code fromInclusive <= startedAt < toExclusive}
     * (half-open range, so adjacent ranges never double count a boundary instant).
     * Served by the existing (athlete_id, started_at) index.
     */
    List<Activity> findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(
            Long athleteId, Instant fromInclusive, Instant toExclusive);

    /** Same half-open range, newest first — for callers that need a deterministic processing order. */
    List<Activity> findByAthleteIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtDesc(
            Long athleteId, Instant fromInclusive, Instant toExclusive);
}

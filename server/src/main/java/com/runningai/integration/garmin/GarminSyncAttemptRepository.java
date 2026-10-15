package com.runningai.integration.garmin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GarminSyncAttemptRepository extends JpaRepository<GarminSyncAttempt, Long> {

    Optional<GarminSyncAttempt> findByAthleteId(Long athleteId);

    /** Used only at startup, to find attempts a dead JVM left claiming to be in flight. */
    List<GarminSyncAttempt> findByOutcome(GarminSyncAttemptOutcome outcome);
}

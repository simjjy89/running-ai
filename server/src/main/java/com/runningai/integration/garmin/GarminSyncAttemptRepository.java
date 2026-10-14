package com.runningai.integration.garmin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GarminSyncAttemptRepository extends JpaRepository<GarminSyncAttempt, Long> {

    Optional<GarminSyncAttempt> findByAthleteId(Long athleteId);
}

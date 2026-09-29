package com.runningai.integration.garmin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GarminSyncStateRepository extends JpaRepository<GarminSyncState, Long> {

    Optional<GarminSyncState> findByAthleteId(Long athleteId);
}

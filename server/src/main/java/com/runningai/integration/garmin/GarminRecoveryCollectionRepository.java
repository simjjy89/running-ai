package com.runningai.integration.garmin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface GarminRecoveryCollectionRepository extends JpaRepository<GarminRecoveryCollection, Long> {

    Optional<GarminRecoveryCollection> findByAthleteIdAndRecoveryDate(Long athleteId, LocalDate recoveryDate);

    /** Inclusive on both ends, oldest first. */
    List<GarminRecoveryCollection> findByAthleteIdAndRecoveryDateBetweenOrderByRecoveryDateAsc(
            Long athleteId, LocalDate from, LocalDate to);
}

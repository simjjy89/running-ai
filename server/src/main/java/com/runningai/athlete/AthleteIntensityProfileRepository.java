package com.runningai.athlete;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface AthleteIntensityProfileRepository extends JpaRepository<AthleteIntensityProfile, Long> {

    Optional<AthleteIntensityProfile> findByAthleteId(Long athleteId);

    /**
     * Row-locked read for the two write paths (Phase 7B-4): a concurrent manual PUT and Garmin
     * merge serialize on the profile row, so each history entry's previous-values snapshot is
     * taken against the value it actually replaced, never against a stale read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AthleteIntensityProfile> findWithLockingByAthleteId(Long athleteId);
}

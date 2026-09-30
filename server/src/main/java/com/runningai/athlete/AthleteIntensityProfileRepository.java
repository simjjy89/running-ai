package com.runningai.athlete;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AthleteIntensityProfileRepository extends JpaRepository<AthleteIntensityProfile, Long> {

    Optional<AthleteIntensityProfile> findByAthleteId(Long athleteId);
}

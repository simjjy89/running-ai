package com.runningai.athlete;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AthleteIntensityProfileHistoryRepository extends JpaRepository<AthleteIntensityProfileHistory, Long> {

    /** The latest event that actually changed the profile (id breaks same-microsecond ties). */
    Optional<AthleteIntensityProfileHistory> findFirstByAthleteIdAndAppliedTrueOrderByRecordedAtDescIdDesc(Long athleteId);

    /** The latest Garmin event, applied or not - freshness evidence, never origin proof. */
    Optional<AthleteIntensityProfileHistory> findFirstByAthleteIdAndSourceOrderByRecordedAtDescIdDesc(
            Long athleteId, AthleteIntensityProfileHistory.Source source);

    /** Full ledger, newest first. Rows are never updated or deleted. */
    List<AthleteIntensityProfileHistory> findByAthleteIdOrderByRecordedAtDescIdDesc(Long athleteId);
}

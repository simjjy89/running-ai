package com.runningai.recovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RecoveryRepository extends JpaRepository<RecoverySnapshot, Long> {

    Optional<RecoverySnapshot> findByAthleteIdAndRecoveryDate(Long athleteId, LocalDate recoveryDate);

    /** Inclusive on both ends, oldest first. */
    List<RecoverySnapshot> findByAthleteIdAndRecoveryDateBetweenOrderByRecoveryDateAsc(
            Long athleteId, LocalDate from, LocalDate to);
}

package com.runningai.recovery;

import com.runningai.athlete.AthleteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Storage of the default athlete's daily recovery snapshots. */
@Service
public class RecoverySnapshotService {

    private final RecoveryRepository repository;
    private final AthleteService athleteService;

    public RecoverySnapshotService(RecoveryRepository repository, AthleteService athleteService) {
        this.repository = repository;
        this.athleteService = athleteService;
    }

    /**
     * Idempotent upsert of one day. No row is created for a day without a single value (an empty
     * row would read as "Garmin had data for this day"), and an unchanged re-sync writes nothing.
     * Merge semantics: {@link RecoverySnapshot#merge}.
     */
    @Transactional
    public RecoveryUpsertResult upsert(LocalDate date, RecoveryDailyValues values) {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Optional<RecoverySnapshot> existing = repository.findByAthleteIdAndRecoveryDate(athleteId, date);
        if (existing.isEmpty()) {
            if (values.isEmpty()) {
                return new RecoveryUpsertResult(false, RecoveryDailyValues.EMPTY);
            }
            RecoverySnapshot created = new RecoverySnapshot(athleteId, date);
            created.merge(values);
            repository.save(created);
            return new RecoveryUpsertResult(true, created.values());
        }
        RecoverySnapshot snapshot = existing.get();
        boolean changed = snapshot.merge(values);
        return new RecoveryUpsertResult(changed, snapshot.values());
    }

    /** Snapshots of the default athlete from {@code from} to {@code to} inclusive, oldest first. */
    @Transactional(readOnly = true)
    public List<RecoverySnapshot> between(LocalDate from, LocalDate to) {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        return repository.findByAthleteIdAndRecoveryDateBetweenOrderByRecoveryDateAsc(athleteId, from, to);
    }
}

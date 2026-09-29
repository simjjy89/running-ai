package com.runningai.integration.garmin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Reads and advances the Garmin incremental sync checkpoint for an athlete. Not
 * exposed through the HTTP API; only {@link GarminIncrementalSyncService} calls it.
 */
@Service
@Transactional(readOnly = true)
public class GarminSyncStateService {

    private final GarminSyncStateRepository repository;

    public GarminSyncStateService(GarminSyncStateRepository repository) {
        this.repository = repository;
    }

    public Optional<GarminSyncState> find(Long athleteId) {
        return repository.findByAthleteId(athleteId);
    }

    /**
     * Creates the checkpoint on the first successful sync, or advances the
     * existing one otherwise. The high-water mark only ever moves forward.
     */
    @Transactional
    public GarminSyncState advance(Long athleteId, Instant candidateHighWater, Instant syncedAt) {
        return repository.findByAthleteId(athleteId)
                .map(state -> {
                    state.advance(candidateHighWater, syncedAt);
                    return state;
                })
                .orElseGet(() -> repository.save(new GarminSyncState(athleteId, candidateHighWater, syncedAt)));
    }
}

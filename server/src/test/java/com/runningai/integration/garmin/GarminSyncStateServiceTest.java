package com.runningai.integration.garmin;

import com.runningai.athlete.AthleteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GarminSyncStateServiceTest {

    @Autowired
    private GarminSyncStateService syncStateService;

    @Autowired
    private GarminSyncStateRepository repository;

    @Autowired
    private AthleteService athleteService;

    private Long athleteId;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        athleteId = athleteService.getDefaultAthlete().getId();
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void noStateExistsBeforeTheFirstAdvance() {
        assertThat(syncStateService.find(athleteId)).isEmpty();
    }

    @Test
    void firstAdvanceCreatesTheCheckpoint() {
        Instant highWater = Instant.parse("2026-09-28T21:30:00Z");
        Instant syncedAt = Instant.parse("2026-09-29T00:00:00Z");

        GarminSyncState state = syncStateService.advance(athleteId, highWater, syncedAt);

        assertThat(state.getAthleteId()).isEqualTo(athleteId);
        assertThat(state.getHighWaterStartedAt()).isEqualTo(highWater);
        assertThat(state.getLastSuccessfulSyncAt()).isEqualTo(syncedAt);
        assertThat(syncStateService.find(athleteId)).isPresent();
    }

    @Test
    void advanceMovesHighWaterForwardOnlyAndAlwaysUpdatesLastSuccessfulSync() {
        Instant firstHighWater = Instant.parse("2026-09-28T21:30:00Z");
        syncStateService.advance(athleteId, firstHighWater, Instant.parse("2026-09-29T00:00:00Z"));

        // an older candidate does not move the high-water mark backwards
        Instant olderCandidate = Instant.parse("2026-09-20T00:00:00Z");
        Instant secondSyncedAt = Instant.parse("2026-09-29T06:00:00Z");
        GarminSyncState afterOlder = syncStateService.advance(athleteId, olderCandidate, secondSyncedAt);
        assertThat(afterOlder.getHighWaterStartedAt()).isEqualTo(firstHighWater);
        assertThat(afterOlder.getLastSuccessfulSyncAt()).isEqualTo(secondSyncedAt);

        // a newer candidate does move it forward
        Instant newerCandidate = Instant.parse("2026-09-30T00:00:00Z");
        Instant thirdSyncedAt = Instant.parse("2026-09-30T06:00:00Z");
        GarminSyncState afterNewer = syncStateService.advance(athleteId, newerCandidate, thirdSyncedAt);
        assertThat(afterNewer.getHighWaterStartedAt()).isEqualTo(newerCandidate);
        assertThat(afterNewer.getLastSuccessfulSyncAt()).isEqualTo(thirdSyncedAt);

        assertThat(repository.count()).isEqualTo(1);   // same row updated in place, never a second one
    }
}

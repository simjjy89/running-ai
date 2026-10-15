-- Phase 8B (Workstream A): two gaps in the Phase 8A attempt ledger that only showed up once the
-- ledger was asked to DRIVE something rather than merely report.
--
-- 1. An attempt had no state between "not started" and "finished". A row was written only after
--    the run ended, so a JVM killed mid-sync (watchdog restart, machine sleep, power loss) left
--    the ledger claiming the LAST attempt was whatever ran before it - the interrupted run simply
--    never happened as far as the database was concerned. STARTED is written before the connector
--    is called and overwritten by the real outcome; a STARTED row still present at the next
--    startup is therefore proof of an interruption, and the startup reconciler turns it into
--    INTERRUPTED. INTERRUPTED is deliberately NOT a failure: the process died, Garmin did not
--    refuse anything, so it must not push the circuit breaker towards blocking.
--
-- 2. consecutive_failure_count counts FAILED only, and that is kept exactly as it was. But a run
--    that completes with unmappable items (PARTIAL) holds the checkpoint back, so the next run
--    re-reads the same window and hits the same unmappable item - forever, while the failure
--    counter sits at 0 because every attempt "completed". consecutive_partial_count makes that
--    stall visible and lets the breaker stop a pointless hourly loop, without overloading the
--    meaning of the existing column.
--
-- Still pure observability in the sense that matters: nothing here decides what to FETCH, and
-- nothing here can move garmin_sync_state. The circuit breaker only decides whether the SCHEDULER
-- may start a tick; a manual sync is never blocked by it.
ALTER TABLE garmin_sync_attempt
    ADD COLUMN consecutive_partial_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE garmin_sync_attempt
    DROP CONSTRAINT ck_garmin_sync_attempt_outcome;

ALTER TABLE garmin_sync_attempt
    ADD CONSTRAINT ck_garmin_sync_attempt_outcome
        CHECK (outcome IN ('STARTED', 'SUCCESS', 'PARTIAL', 'FAILED', 'INTERRUPTED'));

ALTER TABLE garmin_sync_attempt
    DROP CONSTRAINT ck_garmin_sync_attempt_counts;

ALTER TABLE garmin_sync_attempt
    ADD CONSTRAINT ck_garmin_sync_attempt_counts
        CHECK (fetched >= 0 AND created >= 0 AND updated >= 0 AND skipped >= 0 AND failed >= 0
            AND pages_fetched >= 0 AND consecutive_failure_count >= 0 AND consecutive_partial_count >= 0);

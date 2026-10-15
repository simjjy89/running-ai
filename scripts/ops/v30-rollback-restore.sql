-- RunningAI — Phase 8B roll-forward: put back what v30-rollback-quarantine.sql set aside.
--
-- WHEN TO RUN THIS
--   After rolling FORWARD to the Phase 8B jar again, if v30-rollback-quarantine.sql was ever run.
--   Safe to run when the quarantine table is empty or absent-from-use (it inserts nothing).
--
-- SAFETY PROPERTY
--   ON CONFLICT (athlete_id) DO NOTHING. If a sync ran during the rollback window, that athlete
--   already has a NEWER live row; the newer row WINS and the quarantined one stays in quarantine
--   as history. This script can therefore never overwrite a more recent attempt record, and it is
--   idempotent - running it twice changes nothing the second time.
--
-- A restored STARTED row is correct to restore: GarminSyncAttemptReconciler converts it to
-- INTERRUPTED on the next startup, which is exactly what it means.

\set ON_ERROR_STOP on

BEGIN;

INSERT INTO garmin_sync_attempt (
    athlete_id, attempted_at, outcome, failure_code, fetched, created, updated, skipped, failed,
    pages_fetched, checkpoint_advanced, consecutive_failure_count, consecutive_partial_count,
    last_failure_at, last_failure_code, created_at, updated_at)
SELECT (row_data->>'athlete_id')::bigint,
       (row_data->>'attempted_at')::timestamptz,
       row_data->>'outcome',
       row_data->>'failure_code',
       (row_data->>'fetched')::int,
       (row_data->>'created')::int,
       (row_data->>'updated')::int,
       (row_data->>'skipped')::int,
       (row_data->>'failed')::int,
       (row_data->>'pages_fetched')::int,
       (row_data->>'checkpoint_advanced')::boolean,
       (row_data->>'consecutive_failure_count')::int,
       COALESCE((row_data->>'consecutive_partial_count')::int, 0),
       (row_data->>'last_failure_at')::timestamptz,
       row_data->>'last_failure_code',
       (row_data->>'created_at')::timestamptz,
       (row_data->>'updated_at')::timestamptz
FROM garmin_sync_attempt_v30_quarantine
ON CONFLICT (athlete_id) DO NOTHING;

COMMIT;

-- POST-CHECK: what the live ledger says now, and what is still only in quarantine.
SELECT athlete_id, outcome, failure_code, consecutive_failure_count, consecutive_partial_count
FROM garmin_sync_attempt;
SELECT count(*) AS still_quarantined FROM garmin_sync_attempt_v30_quarantine;

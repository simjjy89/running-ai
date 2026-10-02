-- Phase 6H-4: promote the lap fields that describe workout structure out of extra_metrics.
--
-- Phase 6H-1B confirmed live that Garmin states work and rest on the lap itself:
--   intensityType   WARMUP / ACTIVE / RECOVERY / COOLDOWN (live values; the set is Garmin's, not ours)
--   wktIndex        the structured workout the lap was run against
--   wktStepIndex    the step within that workout
-- and that a lap is NOT 1:1 with a step (one live step spanned nine laps, two steps alternated as a
-- repeat block, one index never appeared, and the last lap carried no step at all).
--
-- The analysis engine groups laps into blocks by these values, so they become columns instead of JSON
-- lookups. intensity_type stays a free VARCHAR, deliberately: an unseen Garmin value must widen the
-- data, never break ingestion, so there is no CHECK constraint and no database-level enum here.
-- extra_metrics keeps carrying the source keys unchanged, so nothing is lost by this promotion.
ALTER TABLE activity_lap ADD COLUMN intensity_type VARCHAR(32);
ALTER TABLE activity_lap ADD COLUMN workout_index INTEGER;
ALTER TABLE activity_lap ADD COLUMN workout_step_index INTEGER;

-- Existing rows stay NULL until their stored SPLITS payload is re-normalised through
-- POST /api/v1/garmin/activities/{id}/details/reprocess, which needs no Garmin call.

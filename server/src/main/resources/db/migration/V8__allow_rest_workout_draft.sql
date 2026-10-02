-- Phase 6F.1: a REST draft is a first-class prescription -- a rest day with 0 minutes and no
-- segments -- not a padded dummy workout. V6 required total_duration_minutes > 0, which made a rest
-- day impossible to store. The replacement mirrors WorkoutDraftValidator exactly: a REST draft has
-- exactly 0 minutes, every other draft has a positive duration. (Segments live in JSON; "REST has
-- no segments" is enforced by the validator.) Whether to rest is still decided by the AI coach,
-- never by the schema.
ALTER TABLE workout_draft DROP CONSTRAINT ck_workout_draft_duration_positive;

ALTER TABLE workout_draft ADD CONSTRAINT ck_workout_draft_duration_rest_or_positive CHECK (
    (workout_type = 'REST' AND total_duration_minutes = 0)
    OR (workout_type <> 'REST' AND total_duration_minutes > 0)
);

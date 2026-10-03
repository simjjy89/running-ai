-- Phase 6H-7: audit/reproducibility of which TrainingContext a draft actually saw. Every new draft
-- records the exact context it was built from -- version, canonical JSON snapshot, when it was built
-- and its SHA-256 -- so a later question ("what did the coach actually know?") has a real answer
-- even after the underlying DB rows have since changed.
--
-- All four columns are nullable: an existing draft row predates this column set and cannot be
-- backfilled (what context it actually saw is simply not recoverable), so it keeps NULL rather than
-- a fabricated value. Write-once in practice (set at insert, by WorkoutDraftStore.save; a revision
-- inserts a new row with its own snapshot and never touches an earlier one), but not enforced as a
-- DB constraint since nothing else in workout_draft is either -- the service layer already treats
-- every column but status as immutable after insert.
ALTER TABLE workout_draft ADD COLUMN context_version VARCHAR(32);
ALTER TABLE workout_draft ADD COLUMN context_snapshot ${json_type};
ALTER TABLE workout_draft ADD COLUMN context_built_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE workout_draft ADD COLUMN context_sha256 VARCHAR(64);

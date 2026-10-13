-- Phase 7E-1 STEP 3: make the analysis identity say WHICH ENGINE produced a stored row, and WHICH
-- INPUTS it was produced from.
--
-- The defect this repairs (measured in Phase 7E-0 §3.7 on the real-data copy): analysis_version held
-- the single literal 'RUNNING_ANALYSIS_V1' both before and after V22 added the running-dynamics
-- columns. Every reuse rule in the system compared that one string, so 7 of 8 stored analyses - all
-- computed by the pre-V22 engine, all with NULL dynamics - were considered up to date and were reused
-- forever. Re-running the historical backfill could not repair them (Δ = 0 rows, measured).
--
-- The fix is a policy, not a bigger string. A stored analysis now carries TWO independent facts:
--
--   analysis_version          the output CONTRACT consumers read (field/column shape).
--                             Changes only when the shape changes. Still 'RUNNING_ANALYSIS_V1'.
--   analysis_engine_revision  the LOGIC that produced the numbers. Bumped by whoever changes a
--                             calculator, adds a metric or changes which input is read - any change
--                             that can alter a stored value, even when the contract is untouched.
--                             Revision 1 = the 6H-4 engine; 2 = V22 running dynamics (current).
--
-- plus the third fact that makes "the data changed" detectable without guessing:
--
--   analysis_input_digest     SHA-256 over the analysis inputs (activity identity metrics, every
--                             recorded detail part with its status/row count/sample completeness, and
--                             the athlete LTHR). Re-collecting a stream, adding laps or changing the
--                             LTHR changes the digest; re-running an unchanged activity does not.
--
-- Reuse is then decided by AnalysisReusePolicy: reuse only when contract, engine revision AND input
-- digest all still match; otherwise re-analyse. Nothing in the system compares analysis_version alone
-- any more.
--
-- Existing rows are marked revision 0 (= produced before revisions were tracked) with a NULL digest.
-- Both make them re-analysis targets on the next pass. They are NOT reclassified as revision 1 or 2:
-- which engine produced them is not recorded anywhere, and inferring it from NULL dynamics columns
-- would be a guess. No derived value is changed here - re-analysis recomputes them from the stored
-- samples/laps/zones, which this migration does not touch.
ALTER TABLE activity_analysis ADD COLUMN analysis_engine_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE activity_analysis ADD COLUMN analysis_input_digest VARCHAR(64);

-- The default exists only so the NOT NULL column can be added to existing rows. New rows always state
-- their revision explicitly; dropping the default keeps an unset revision an error rather than a 0.
ALTER TABLE activity_analysis ALTER COLUMN analysis_engine_revision DROP DEFAULT;

ALTER TABLE activity_analysis ADD CONSTRAINT ck_activity_analysis_engine_revision
    CHECK (analysis_engine_revision >= 0);

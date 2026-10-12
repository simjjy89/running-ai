-- Phase 7B-3: Garmin running-dynamics evidence (stride length, vertical oscillation, vertical ratio,
-- ground contact time, running power), derived per lap from each lap's own extra_metrics (Garmin's
-- SPLITS payload) plus the existing average_power/max_power lap columns. Measurement only, same rule
-- as the rest of this table: no rating, no good/bad classification, nothing diagnosing an injury
-- cause. Every column is nullable - a non-running activity, or a run whose device did not report a
-- given dynamic, leaves the corresponding columns NULL, never zero and never estimated.
--
-- "Halves" here are split by lap ORDER (first half of laps vs second half), not by elapsed time like
-- the session-level halves above - laps have no finer time range to split further. This is a
-- deliberate, disclosed difference from first_half_avg_hr etc., never presented as the same split.
ALTER TABLE activity_analysis ADD COLUMN laps_with_dynamics_count INTEGER;

ALTER TABLE activity_analysis ADD COLUMN avg_stride_length_cm DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN first_half_avg_stride_length_cm DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN second_half_avg_stride_length_cm DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN stride_length_change_percent DOUBLE PRECISION;
-- Later lap's value minus the earlier lap's, for the pair of laps run at the most similar pace
-- (rounded to the nearest 15s/km) that are also at least 3 laps apart - a genuine "same effort,
-- different point in the session" comparison. NULL when no such pair exists (common, and expected,
-- for a steady-state session).
ALTER TABLE activity_analysis ADD COLUMN same_pace_stride_length_change_cm DOUBLE PRECISION;

ALTER TABLE activity_analysis ADD COLUMN avg_vertical_oscillation_cm DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN first_half_avg_vertical_oscillation_cm DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN second_half_avg_vertical_oscillation_cm DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN vertical_oscillation_change_percent DOUBLE PRECISION;

ALTER TABLE activity_analysis ADD COLUMN avg_vertical_ratio_percent DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN first_half_avg_vertical_ratio_percent DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN second_half_avg_vertical_ratio_percent DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN vertical_ratio_change_percent DOUBLE PRECISION;

ALTER TABLE activity_analysis ADD COLUMN avg_ground_contact_time_ms DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN first_half_avg_ground_contact_time_ms DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN second_half_avg_ground_contact_time_ms DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN ground_contact_time_change_percent DOUBLE PRECISION;

-- Running power: from the existing LapData.averagePower/maxPower (Garmin lap summary fields,
-- Phase 6H-4), not re-derived from extra_metrics.
ALTER TABLE activity_analysis ADD COLUMN avg_running_power_watts DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN max_running_power_watts DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN first_half_avg_running_power_watts DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN second_half_avg_running_power_watts DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN running_power_change_percent DOUBLE PRECISION;
ALTER TABLE activity_analysis ADD COLUMN same_pace_running_power_change_watts DOUBLE PRECISION;

-- Existing rows stay NULL until their activity is re-analysed via POST /api/v1/activities/{id}/analysis
-- (no Garmin call needed - the source lap/power data is already stored).

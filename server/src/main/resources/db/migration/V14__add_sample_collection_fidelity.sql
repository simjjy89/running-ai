-- Phase 6H-1C: record how complete a stored sample stream is, instead of inferring it later.
--
-- Garmin's sample endpoint caps the number of returned points at the requested maxChartSize and
-- down-samples beyond it (Phase 6H-1B measured 1399 of 2784 native points on a 2783 s run at the
-- library default of 2000, and all 2784 at 20000). The payload itself reports both the number of
-- points it returned (metricsCount) and the activity's native point count (totalMetricsCount), so
-- completeness is a fact of the response and is never assumed from the request.
--
-- These four columns only carry meaning for payload_type = 'ACTIVITY_DETAILS_STREAM'; every other
-- part leaves them NULL. That is expressed as nullability rather than a CHECK over payload_type: a
-- conditional constraint here would have to be re-stated for every future part type, and the
-- ingestion service is the single writer.
--
--   requested_max_chart_size    what RunningAI asked Garmin for; NULL when it is not known to have
--                               been recorded (a reprocess of a payload fetched before this phase)
--   source_metrics_count        payload.metricsCount - points the response says it returned
--   source_total_metrics_count  payload.totalMetricsCount - the activity's native point count
--   sample_completeness         FULL / DOWNSAMPLED / UNKNOWN, derived from the stored row count
--                               against source_total_metrics_count
--
-- Existing rows keep NULL: nothing is back-filled, because the payloads behind them were fetched
-- before the request size was recorded and classifying them now would be a guess. A reprocess
-- recomputes completeness from the stored raw payload without contacting Garmin.
ALTER TABLE activity_detail_collection ADD COLUMN requested_max_chart_size INTEGER;
ALTER TABLE activity_detail_collection ADD COLUMN source_metrics_count INTEGER;
ALTER TABLE activity_detail_collection ADD COLUMN source_total_metrics_count INTEGER;
ALTER TABLE activity_detail_collection ADD COLUMN sample_completeness VARCHAR(16);

ALTER TABLE activity_detail_collection ADD CONSTRAINT ck_activity_detail_collection_sample_completeness CHECK (
    sample_completeness IS NULL OR sample_completeness IN ('FULL', 'DOWNSAMPLED', 'UNKNOWN'));

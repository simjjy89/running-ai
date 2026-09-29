package com.runningai.activity;

/**
 * Outcome of {@link ActivityService#upsertExternalActivity(NormalizedActivity)}:
 * the persisted activity and whether it was inserted ({@code created}) or an
 * existing row was updated in place.
 */
public record ActivityUpsertResult(Activity activity, boolean created) {
}

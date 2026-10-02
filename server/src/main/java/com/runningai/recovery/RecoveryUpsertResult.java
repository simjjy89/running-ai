package com.runningai.recovery;

/**
 * Outcome of storing one day's recovery metrics.
 *
 * @param updated true when a row was inserted or at least one stored value changed
 * @param values  what is stored for the day afterwards ({@link RecoveryDailyValues#EMPTY} when
 *                nothing has ever been stored for it)
 */
public record RecoveryUpsertResult(boolean updated, RecoveryDailyValues values) {
}

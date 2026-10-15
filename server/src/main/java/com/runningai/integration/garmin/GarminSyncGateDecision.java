package com.runningai.integration.garmin;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Whether an AUTOMATIC Garmin sync may start right now, and if not, why (Phase 8B).
 * <p>
 * A decision is a statement about the scheduler, never about the athlete or the data. It is derived
 * fresh from the attempt ledger on every read and is not persisted: there is one source of truth
 * (what actually happened), and no second copy of it that could drift.
 *
 * @param allowed    true when a scheduled tick may run
 * @param reason     machine-readable reason; {@code ALLOWED} when nothing is blocking
 * @param detail     one short human sentence, free of credentials, tokens and payload text
 * @param retryAfter when a time-based block lifts, or null when only a human can lift it
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GarminSyncGateDecision(
        boolean allowed,
        GarminSyncGateReason reason,
        String detail,
        Instant retryAfter
) {

    /** Named {@code allow} rather than {@code allowed} because the component accessor owns that name. */
    public static GarminSyncGateDecision allow() {
        return new GarminSyncGateDecision(true, GarminSyncGateReason.ALLOWED, "No automatic block is in effect", null);
    }

    public static GarminSyncGateDecision blocked(GarminSyncGateReason reason, String detail, Instant retryAfter) {
        return new GarminSyncGateDecision(false, reason, detail, retryAfter);
    }
}

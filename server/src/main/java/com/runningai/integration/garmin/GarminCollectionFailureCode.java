package com.runningai.integration.garmin;

/**
 * Turns a failed Garmin collection into a short, storable reason code (Phase 8A).
 * <p>
 * Deliberately finer-grained than {@link GarminSyncExceptionHandler}'s HTTP error contract, which
 * collapses {@code UPSTREAM_ERROR}, {@code CONNECTOR_ERROR} and {@code INVALID_RESPONSE} into one
 * {@code GARMIN_UPSTREAM_ERROR} response: this value is stored for diagnosis, where "the connector
 * returned a non-JSON body" and "Garmin answered 502" are different problems with different fixes.
 * <p>
 * Only the exception's type and reason are used - never its message - so a code can never carry a
 * credential, a token or a payload excerpt into the database.
 */
final class GarminCollectionFailureCode {

    /** The {@code failure_code} columns are VARCHAR(64). */
    private static final int MAX_LENGTH = 64;

    private GarminCollectionFailureCode() {
    }

    static String of(RuntimeException e) {
        String code = switch (e) {
            case GarminConnectorException connector -> "GARMIN_" + connector.getReason().name();
            case GarminIncrementalSyncException ignored -> "INCREMENTAL_WINDOW_INCOMPLETE";
            default -> "UNEXPECTED_" + e.getClass().getSimpleName();
        };
        return code.length() <= MAX_LENGTH ? code : code.substring(0, MAX_LENGTH);
    }
}

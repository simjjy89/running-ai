package com.runningai.integration.garmin;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which Garmin-touching operations are running in this JVM right now (Phase 8B).
 * <p>
 * Each Garmin operation already has its own lock, and those locks are what guarantee correctness -
 * this registry does not replace them and cannot. It answers a different question: the activity
 * sync and the recovery sync hold SEPARATE locks, so an hourly tick can land in the middle of a
 * 28-day recovery backfill (140 sequential connector calls) and add requests to an account that is
 * already being worked hard. A scheduled tick consults this and simply waits for the next one;
 * that is always safe, because a skipped incremental sync loses nothing - the 7-day overlap covers
 * the gap.
 * <p>
 * Deliberately advisory and in-memory. A wrong answer here can only cost one skipped automatic
 * tick or allow the overlap that exists today; it can never corrupt data, and it never blocks a
 * manual request. Registration is in a {@code finally}, so a crash inside an operation cannot
 * leave the registry claiming it is still running - and a JVM restart clears it entirely.
 */
@Component
public class GarminActivityRegistry {

    /** Name of an operation for logging and for the sync-status report. */
    public enum Operation {
        INCREMENTAL_SYNC,
        RECOVERY_SYNC
    }

    private final Set<Operation> running = ConcurrentHashMap.newKeySet();

    /** Marks {@code operation} as running until the returned handle is closed. */
    public Handle enter(Operation operation) {
        running.add(operation);
        return () -> running.remove(operation);
    }

    /** True when any Garmin operation other than {@code self} is in flight. */
    public boolean busyOtherThan(Operation self) {
        return running.stream().anyMatch(o -> o != self);
    }

    public Set<Operation> running() {
        return Set.copyOf(running);
    }

    /** Closeable that never throws, so it is safe in a try-with-resources around a failing call. */
    @FunctionalInterface
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}

package com.runningai.integration.intervals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Stateful in-memory Intervals.icu for publisher tests: a calendar of events with counters for every
 * write, scripted failures (including "the write was applied but the response was lost") and a hook that
 * lets the server store something different from what was sent (to exercise readback verification).
 */
class FakeIntervalsWorkoutClient implements IntervalsWorkoutClient {

    final Map<String, IntervalsEvent> events = new LinkedHashMap<>();
    int listCalls;
    int getCalls;
    int createCalls;
    int updateCalls;
    private int nextId = 1000;

    IntervalsException listFailure;
    IntervalsException createFailure;
    boolean createFailureStillApplies;
    IntervalsException updateFailure;
    /** Transforms the description the server stores on create/update (null = store faithfully). */
    UnaryOperator<String> storedDescription;
    /** Replaces the marker the server keeps on create/update (null = keep). */
    String storedExternalId;
    /** Fail lookups after this many successful list calls (-1 = never). */
    int failListAfter = -1;
    IntervalsException laterListFailure;

    IntervalsEvent seed(LocalDate date, String name, String description, String externalId) {
        String id = String.valueOf(nextId++);
        IntervalsEvent event = new IntervalsEvent(id, date, name, "Run", description, externalId);
        events.put(id, event);
        return event;
    }

    int writeCalls() {
        return createCalls + updateCalls;
    }

    List<IntervalsEvent> on(LocalDate date) {
        return events.values().stream().filter(e -> e.scheduledDate().equals(date)).toList();
    }

    @Override
    public List<IntervalsEvent> listWorkouts(LocalDate date) {
        listCalls++;
        if (listFailure != null) {
            throw listFailure;
        }
        if (failListAfter >= 0 && listCalls > failListAfter) {
            throw laterListFailure;
        }
        return new ArrayList<>(on(date));
    }

    @Override
    public IntervalsEvent get(String eventId) {
        getCalls++;
        IntervalsEvent event = events.get(eventId);
        if (event == null) {
            throw new IntervalsException(IntervalsException.Reason.CLIENT_ERROR, 404, "Intervals.icu responded 404");
        }
        return event;
    }

    @Override
    public IntervalsEvent create(IntervalsEventDraft draft) {
        createCalls++;
        if (createFailure != null) {
            if (createFailureStillApplies) {
                store(String.valueOf(nextId++), draft);
            }
            throw createFailure;
        }
        return store(String.valueOf(nextId++), draft);
    }

    @Override
    public IntervalsEvent update(String eventId, IntervalsEventDraft draft) {
        updateCalls++;
        if (updateFailure != null) {
            throw updateFailure;
        }
        return store(eventId, draft);
    }

    private IntervalsEvent store(String id, IntervalsEventDraft draft) {
        String description = storedDescription == null ? draft.description() : storedDescription.apply(draft.description());
        String externalId = storedExternalId != null ? storedExternalId : draft.externalId();
        IntervalsEvent event = new IntervalsEvent(id, draft.scheduledDate(), draft.name(), draft.type(), description, externalId);
        events.put(id, event);
        return event;
    }
}

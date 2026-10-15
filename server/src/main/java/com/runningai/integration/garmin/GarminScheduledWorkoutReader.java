package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure reading of Garmin's scheduled-workout calendar payload (Phase 8B). Garmin field names end
 * here, as they do in every other mapper in this package.
 * <p>
 * The payload's shape is STATIC_SOURCE_CONFIRMED against python-garminconnect 0.3.16 - its own
 * {@code get_next_scheduled_workout} walks {@code calendarItems[]} and keeps the entries whose
 * {@code itemType} is {@code "workout"} - and is NOT live-verified. Everything here therefore reads
 * defensively: a missing field is a null, an unexpected shape is an empty list, and nothing is
 * inferred from a field that is not present.
 */
@Component
public class GarminScheduledWorkoutReader {

    /** One entry of Garmin's calendar for a date. Null fields mean Garmin did not supply them. */
    public record ScheduledWorkout(LocalDate date, String title, String sport, Long workoutId) {
    }

    /**
     * The workout entries Garmin's calendar holds for {@code date}.
     * <p>
     * An empty list means Garmin's calendar service returned no workout for that day. It is NOT
     * proof that none exists: python-garminconnect documents that this service only returns
     * workouts already committed to the visible calendar, and an Intervals.icu push may also simply
     * not have happened yet. Callers must report the absence as an absence.
     */
    public List<ScheduledWorkout> workoutsOn(JsonNode monthPayload, LocalDate date) {
        List<ScheduledWorkout> found = new ArrayList<>();
        if (monthPayload == null) {
            return found;
        }
        JsonNode items = monthPayload.path("calendarItems");
        if (!items.isArray()) {
            return found;
        }
        for (JsonNode item : items) {
            if (!"workout".equals(text(item, "itemType"))) {
                continue;
            }
            LocalDate itemDate = date(item);
            if (itemDate == null || !itemDate.equals(date)) {
                continue;
            }
            found.add(new ScheduledWorkout(itemDate, text(item, "title"), text(item, "sportTypeKey"),
                    number(item, "workoutId")));
        }
        return found;
    }

    private static LocalDate date(JsonNode item) {
        String raw = text(item, "date");
        if (raw == null) {
            return null;
        }
        try {
            // Tolerates a date-time by taking the date part: the field has only ever been observed
            // as yyyy-MM-dd in the library source, and a surprise must not become an exception.
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }
}

package com.runningai.integration.intervals;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Explicit athlete-local date to publish; there is deliberately no implicit "today". */
public record WorkoutPublishRequest(@NotNull(message = "date is required") LocalDate date) {
}

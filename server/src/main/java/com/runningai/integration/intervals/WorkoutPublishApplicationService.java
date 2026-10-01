package com.runningai.integration.intervals;

import com.runningai.training.StructuredWorkout;
import com.runningai.training.StructuredWorkoutMapper;
import com.runningai.training.TargetedWorkoutPrescription;
import com.runningai.training.WorkoutIntensityTargetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates one operator-triggered workout publish by reusing the verified pipeline unchanged:
 * {@link WorkoutIntensityTargetService} (prescription with targets) → {@link StructuredWorkoutMapper} →
 * {@link IntervalsWorkoutRenderer} → {@link IntervalsWorkoutPublisher}. It recomputes and formats nothing itself
 * and never talks to Intervals directly; create/update/no-change idempotency belongs to the publisher.
 * <p>
 * Safety: off unless {@code running-ai.workout-publishing.enabled} is true, and at most one publish per date runs at
 * a time in this JVM (in-memory per-date guard, single-instance deployment, no distributed lock): a concurrent
 * request for the same date fails immediately instead of racing the publisher's lookup-then-create. Different
 * dates do not block each other. Not {@code @Transactional}: the prescription service owns its read transaction and
 * no database transaction may stay open across the Intervals HTTP calls.
 */
@Service
public class WorkoutPublishApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WorkoutPublishApplicationService.class);

    private final WorkoutPublishProperties properties;
    private final WorkoutIntensityTargetService targetService;
    private final StructuredWorkoutMapper mapper;
    private final IntervalsWorkoutRenderer renderer;
    private final IntervalsWorkoutPublisher publisher;
    private final Set<LocalDate> inFlight = ConcurrentHashMap.newKeySet();

    public WorkoutPublishApplicationService(WorkoutPublishProperties properties,
                                            WorkoutIntensityTargetService targetService,
                                            StructuredWorkoutMapper mapper,
                                            IntervalsWorkoutRenderer renderer,
                                            IntervalsWorkoutPublisher publisher) {
        this.properties = properties;
        this.targetService = targetService;
        this.mapper = mapper;
        this.renderer = renderer;
        this.publisher = publisher;
    }

    public WorkoutPublishResponse publish(LocalDate scheduledDate) {
        if (scheduledDate == null) {
            throw new IllegalArgumentException("scheduledDate is required");
        }
        if (!properties.enabled()) {
            throw new WorkoutPublishDisabledException();
        }
        if (!inFlight.add(scheduledDate)) {
            log.info("Workout publish rejected: date={} already running", scheduledDate);
            throw new WorkoutPublishAlreadyRunningException(scheduledDate);
        }
        try {
            TargetedWorkoutPrescription prescription = targetService.targetedPrescribe(scheduledDate);
            StructuredWorkout workout = mapper.map(prescription);
            RenderedIntervalsWorkout rendered = renderer.render(workout);
            IntervalsPublishResult result = publisher.publish(scheduledDate, rendered);
            log.info("Workout publish: date={} operation={} verified={}", scheduledDate, result.operation(), result.verified());
            return new WorkoutPublishResponse(scheduledDate, result.operation(), result.verified(),
                    workout.intent(), workout.steps().size());
        } finally {
            inFlight.remove(scheduledDate);
        }
    }
}

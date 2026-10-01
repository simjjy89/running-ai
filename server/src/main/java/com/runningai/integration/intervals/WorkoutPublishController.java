package com.runningai.integration.intervals;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual operational trigger: publishes the workout for one explicit date through the canonical Spring pipeline.
 * A mutation, hence POST only. No authentication exists on this API: keep it on a private network, never expose it
 * to the public Internet.
 */
@RestController
@RequestMapping("/api/v1/workout-publish")
public class WorkoutPublishController {

    private final WorkoutPublishApplicationService service;

    public WorkoutPublishController(WorkoutPublishApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public WorkoutPublishResponse publish(@Valid @RequestBody WorkoutPublishRequest request) {
        return service.publish(request.date());
    }
}

package com.runningai.training;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Read-only workout intensity targets. {@code date} is athlete-local (yyyy-MM-dd); default is the athlete-local today. */
@RestController
@RequestMapping("/api/v1/workout-intensity-targets")
public class WorkoutIntensityTargetController {

    private final WorkoutIntensityTargetService service;

    public WorkoutIntensityTargetController(WorkoutIntensityTargetService service) {
        this.service = service;
    }

    @GetMapping
    public TargetedWorkoutPrescription targets(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.targetedPrescribe(date != null ? date : service.today());
    }
}

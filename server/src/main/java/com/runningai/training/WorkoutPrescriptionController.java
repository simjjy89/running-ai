package com.runningai.training;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Read-only workout structure. {@code date} is athlete-local (yyyy-MM-dd); default is the athlete-local today. */
@RestController
@RequestMapping("/api/v1/workout-prescription")
public class WorkoutPrescriptionController {

    private final WorkoutPrescriptionService service;

    public WorkoutPrescriptionController(WorkoutPrescriptionService service) {
        this.service = service;
    }

    @GetMapping
    public WorkoutPrescription prescribe(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.prescribe(date != null ? date : service.today());
    }
}

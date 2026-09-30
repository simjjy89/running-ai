package com.runningai.training;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Read-only training state. {@code date} is athlete-local (yyyy-MM-dd); default is the athlete-local today. */
@RestController
@RequestMapping("/api/v1/training-state")
public class TrainingStateController {

    private final TrainingStateService trainingStateService;

    public TrainingStateController(TrainingStateService trainingStateService) {
        this.trainingStateService = trainingStateService;
    }

    @GetMapping
    public TrainingState state(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return trainingStateService.state(date != null ? date : trainingStateService.today());
    }
}

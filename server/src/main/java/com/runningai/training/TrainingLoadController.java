package com.runningai.training;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Read-only training load views. {@code date} is athlete-local (yyyy-MM-dd); default is the athlete-local today. */
@RestController
@RequestMapping("/api/v1/training-load")
public class TrainingLoadController {

    private final TrainingLoadService trainingLoadService;

    public TrainingLoadController(TrainingLoadService trainingLoadService) {
        this.trainingLoadService = trainingLoadService;
    }

    @GetMapping
    public TrainingLoadSummary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return trainingLoadService.summary(date != null ? date : trainingLoadService.today());
    }

    @GetMapping("/weekly")
    public WeeklyTrainingSummary weekly(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return trainingLoadService.weekly(date != null ? date : trainingLoadService.today());
    }
}

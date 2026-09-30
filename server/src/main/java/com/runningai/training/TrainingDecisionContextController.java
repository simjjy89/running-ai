package com.runningai.training;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Read-only decision context. {@code date} is athlete-local (yyyy-MM-dd); default is the athlete-local today. */
@RestController
@RequestMapping("/api/v1/training-decision-context")
public class TrainingDecisionContextController {

    private final TrainingDecisionContextService service;

    public TrainingDecisionContextController(TrainingDecisionContextService service) {
        this.service = service;
    }

    @GetMapping
    public TrainingDecisionContext context(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.context(date != null ? date : service.today());
    }
}

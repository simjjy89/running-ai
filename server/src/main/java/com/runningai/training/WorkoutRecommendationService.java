package com.runningai.training;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Chooses today's workout intent from the {@link TrainingDecisionContext} only (no repository or
 * load-service access, no Garmin, nothing persisted or cached). The selection rules live in
 * {@link WorkoutRecommendationPolicy}.
 */
@Service
@Transactional(readOnly = true)
public class WorkoutRecommendationService {

    private final TrainingDecisionContextService contextService;

    public WorkoutRecommendationService(TrainingDecisionContextService contextService) {
        this.contextService = contextService;
    }

    public LocalDate today() {
        return contextService.today();
    }

    public WorkoutRecommendation recommend(LocalDate asOfDate) {
        return WorkoutRecommendationPolicy.recommend(contextService.context(asOfDate));
    }
}

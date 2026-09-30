package com.runningai.training;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Builds the exact-duration structure from the {@link WorkoutRecommendation} only. It makes no
 * decision of its own (the intent comes from the recommendation), has no repository, Garmin or
 * clock access, and persists or caches nothing. Structure rules live in {@link WorkoutPrescriptionPolicy}.
 */
@Service
@Transactional(readOnly = true)
public class WorkoutPrescriptionService {

    private final WorkoutRecommendationService recommendationService;

    public WorkoutPrescriptionService(WorkoutRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    public LocalDate today() {
        return recommendationService.today();
    }

    public WorkoutPrescription prescribe(LocalDate asOfDate) {
        return WorkoutPrescriptionPolicy.prescribe(recommendationService.recommend(asOfDate));
    }
}

package com.runningai.training;

import com.runningai.athlete.AthleteIntensityProfileResponse;
import com.runningai.athlete.AthleteIntensityProfileService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Builds {@link TargetedWorkoutPrescription} from the existing {@link WorkoutPrescriptionService}
 * and the athlete's current {@link AthleteIntensityProfileService} profile only. No direct
 * repository, Garmin, training-load or training-state access, and nothing is persisted or
 * cached: a profile update is reflected on the very next call.
 */
@Service
@Transactional(readOnly = true)
public class WorkoutIntensityTargetService {

    private final WorkoutPrescriptionService prescriptionService;
    private final AthleteIntensityProfileService profileService;

    public WorkoutIntensityTargetService(WorkoutPrescriptionService prescriptionService,
                                          AthleteIntensityProfileService profileService) {
        this.prescriptionService = prescriptionService;
        this.profileService = profileService;
    }

    public LocalDate today() {
        return prescriptionService.today();
    }

    /**
     * Always uses the athlete's *current* intensity profile, even for a historical
     * {@code asOfDate}: no profile history is kept (see the work order's limitations).
     */
    public TargetedWorkoutPrescription targetedPrescribe(LocalDate asOfDate) {
        WorkoutPrescription prescription = prescriptionService.prescribe(asOfDate);
        AthleteIntensityProfileResponse profile = profileService.getDefaultProfile();
        return WorkoutIntensityTargetPolicy.apply(prescription, profile);
    }
}

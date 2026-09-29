package com.runningai.activity;

import com.runningai.athlete.AthleteService;
import com.runningai.common.exception.DuplicateResourceException;
import com.runningai.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final AthleteService athleteService;

    public ActivityService(ActivityRepository activityRepository, AthleteService athleteService) {
        this.activityRepository = activityRepository;
        this.athleteService = athleteService;
    }

    @Transactional
    public ActivityResponse create(ActivityCreateRequest request) {
        if (activityRepository.existsByExternalSourceAndExternalId(request.externalSource(), request.externalId())) {
            throw new DuplicateResourceException("DUPLICATE_ACTIVITY",
                    "Activity already exists: " + request.externalSource() + "/" + request.externalId());
        }
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Activity activity = new Activity(
                athleteId,
                request.externalSource(),
                request.externalId(),
                request.activityType(),
                request.startedAt().toInstant(),
                request.durationSeconds(),
                request.distanceMeters(),
                request.averageHeartRate(),
                request.maxHeartRate()
        );
        return ActivityResponse.from(activityRepository.save(activity));
    }

    public ActivityResponse get(Long id) {
        return activityRepository.findById(id)
                .map(ActivityResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "Activity not found: " + id));
    }

    public List<ActivityResponse> list() {
        return activityRepository.findAllByOrderByStartedAtDesc().stream()
                .map(ActivityResponse::from)
                .toList();
    }
}

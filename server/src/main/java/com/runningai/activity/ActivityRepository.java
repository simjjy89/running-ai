package com.runningai.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    boolean existsByExternalSourceAndExternalId(ExternalSource externalSource, String externalId);

    Optional<Activity> findByExternalSourceAndExternalId(ExternalSource externalSource, String externalId);

    List<Activity> findAllByOrderByStartedAtDesc();
}

package com.runningai.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    boolean existsByExternalSourceAndExternalId(ExternalSource externalSource, String externalId);

    List<Activity> findAllByOrderByStartedAtDesc();
}

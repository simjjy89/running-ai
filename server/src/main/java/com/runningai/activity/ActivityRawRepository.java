package com.runningai.activity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActivityRawRepository extends JpaRepository<ActivityRaw, Long> {

    Optional<ActivityRaw> findByExternalSourceAndExternalId(ExternalSource externalSource, String externalId);
}

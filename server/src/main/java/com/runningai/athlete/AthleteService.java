package com.runningai.athlete;

import com.runningai.common.config.RunningAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AthleteService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AthleteService.class);

    private final AthleteRepository athleteRepository;
    private final RunningAiProperties.DefaultAthlete defaultAthlete;

    public AthleteService(AthleteRepository athleteRepository, RunningAiProperties properties) {
        this.athleteRepository = athleteRepository;
        this.defaultAthlete = properties.defaultAthlete();
    }

    /**
     * Ensures the configured default athlete exists once the application starts,
     * so that activity ingestion can run without any manual setup.
     */
    @Override
    public void run(ApplicationArguments args) {
        Athlete athlete = getDefaultAthlete();
        log.info("Default athlete ready: id={}, name={}, timezone={}",
                athlete.getId(), athlete.getName(), athlete.getTimezone());
    }

    @Transactional
    public Athlete getDefaultAthlete() {
        return athleteRepository.findByName(defaultAthlete.name())
                .orElseGet(() -> athleteRepository.save(
                        new Athlete(defaultAthlete.name(), defaultAthlete.timezone())));
    }
}

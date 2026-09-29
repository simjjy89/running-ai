package com.runningai;

import com.runningai.athlete.AthleteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RunningAiApplicationTests {

    @Autowired
    private AthleteService athleteService;

    @Test
    void contextLoadsAndDefaultAthleteIsProvisioned() {
        var athlete = athleteService.getDefaultAthlete();

        assertThat(athlete.getId()).isNotNull();
        assertThat(athlete.getName()).isEqualTo("default");
        assertThat(athlete.getTimezone()).isEqualTo("Asia/Seoul");
        assertThat(athlete.getCreatedAt()).isNotNull();
        assertThat(athlete.getUpdatedAt()).isNotNull();
    }
}

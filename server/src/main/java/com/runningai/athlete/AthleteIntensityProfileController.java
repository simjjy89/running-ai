package com.runningai.athlete;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Single-athlete architecture: no athlete id in the URL or the response. */
@RestController
@RequestMapping("/api/v1/athlete/intensity-profile")
public class AthleteIntensityProfileController {

    private final AthleteIntensityProfileService service;

    public AthleteIntensityProfileController(AthleteIntensityProfileService service) {
        this.service = service;
    }

    @GetMapping
    public AthleteIntensityProfileResponse get() {
        return service.getDefaultProfile();
    }

    @PutMapping
    public AthleteIntensityProfileResponse replace(@Valid @RequestBody AthleteIntensityProfileRequest request) {
        return service.replaceDefaultProfile(request);
    }
}

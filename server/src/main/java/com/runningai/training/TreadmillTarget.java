package com.runningai.training;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Treadmill operational defaults for a running segment. {@code minSpeedKph}/{@code maxSpeedKph}
 * are {@code null} when no pace target is available; the incline range is always
 * provided for a running segment regardless of the athlete's profile (see
 * {@link RunningIntensityTargetPolicy}) and does not claim outdoor-equivalent load.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TreadmillTarget(Double minSpeedKph, Double maxSpeedKph, double minInclinePercent, double maxInclinePercent) {
}

package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.recovery.RecoveryDailyValues;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure normalisation of the connector's {@code /recovery} contract. Synthetic values only. */
class GarminRecoveryMapperTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);
    private final ObjectMapper objectMapper = new ObjectMapper();

    static final String FULL = """
            {"date": "2026-10-02", "metrics": {
              "hrv": {"status": "OK", "data": {"lastNightAvg": 52.0, "weeklyAvg": 48.5, "status": "BALANCED"}},
              "sleep": {"status": "OK", "data": {"sleepTimeSeconds": 25200, "sleepScore": 84}},
              "restingHeartRate": {"status": "OK", "data": {"value": 52}},
              "bodyBattery": {"status": "OK", "data": {"highest": 81, "lowest": 40, "charged": 58, "drained": 32}},
              "stress": {"status": "OK", "data": {"avgStressLevel": 29, "maxStressLevel": 88}}}}
            """;

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    @Test
    void mapsEveryMetricInGarminUnits() throws Exception {
        GarminRecoveryDay day = GarminRecoveryMapper.map(json(FULL), DAY);

        assertThat(day.date()).isEqualTo(DAY);
        assertThat(day.values()).isEqualTo(new RecoveryDailyValues(52.0, 48.5, "BALANCED", 25200, 84, 52, 81, 40, 58, 32, 29, 88));
        assertThat(day.availableMetrics()).containsExactly("hrv", "sleep", "restingHeartRate", "bodyBattery", "stress");
        assertThat(day.unavailableMetrics()).isEmpty();
    }

    @Test
    void noDataErrorAndMalformedMetricsStayNullWithTheirReason() throws Exception {
        String body = """
                {"date": "2026-10-02", "metrics": {
                  "hrv": {"status": "NO_DATA", "data": null},
                  "sleep": {"status": "ERROR", "data": null, "error": "GARMIN_UPSTREAM_ERROR"},
                  "restingHeartRate": {"status": "MALFORMED", "data": null},
                  "bodyBattery": {"status": "OK", "data": {"highest": 70}},
                  "stress": {"status": "SOMETHING_NEW", "data": {"avgStressLevel": 30}}}}
                """;

        GarminRecoveryDay day = GarminRecoveryMapper.map(json(body), DAY);

        assertThat(day.values()).isEqualTo(new RecoveryDailyValues(null, null, null, null, null, null, 70, null, null, null, null, null));
        assertThat(day.availableMetrics()).containsExactly("bodyBattery");
        assertThat(day.unavailableMetrics()).containsExactly(
                org.assertj.core.api.Assertions.entry("hrv", "NO_DATA"),
                org.assertj.core.api.Assertions.entry("sleep", "ERROR"),
                org.assertj.core.api.Assertions.entry("restingHeartRate", "MALFORMED"),
                org.assertj.core.api.Assertions.entry("stress", "MALFORMED"));
    }

    @Test
    void missingMetricsObjectMeansEverythingUnavailable() throws Exception {
        GarminRecoveryDay day = GarminRecoveryMapper.map(json("{\"date\": \"2026-10-02\"}"), DAY);

        assertThat(day.values().isEmpty()).isTrue();
        assertThat(day.availableMetrics()).isEmpty();
        assertThat(day.unavailableMetrics()).hasSize(5).containsValue("MALFORMED");
    }

    @Test
    void invalidValuesBecomeNullNeverDefaults() throws Exception {
        String body = """
                {"date": "2026-10-02", "metrics": {
                  "hrv": {"status": "OK", "data": {"lastNightAvg": "52", "weeklyAvg": -1, "status": "<b>x</b>"}},
                  "sleep": {"status": "OK", "data": {"sleepTimeSeconds": true, "sleepScore": null}},
                  "restingHeartRate": {"status": "OK", "data": {"value": 0}},
                  "bodyBattery": {"status": "OK", "data": "full"},
                  "stress": {"status": "OK", "data": {"avgStressLevel": 1e30}}}}
                """;

        GarminRecoveryDay day = GarminRecoveryMapper.map(json(body), DAY);

        assertThat(day.values().isEmpty()).isTrue();
        assertThat(day.availableMetrics()).isEmpty();
    }

    @Test
    void hrvStatusIsNormalisedToUpperCaseToken() throws Exception {
        String body = FULL.replace("\"BALANCED\"", "\"unbalanced\"");

        assertThat(GarminRecoveryMapper.map(json(body), DAY).values().hrvStatus()).isEqualTo("UNBALANCED");
    }

    @Test
    void aBodyForAnotherDayIsRejectedSoItIsNeverStoredUnderTheWrongDate() throws Exception {
        assertThatThrownBy(() -> GarminRecoveryMapper.map(json(FULL), DAY.minusDays(1)))
                .isInstanceOf(GarminConnectorException.class)
                .extracting(e -> ((GarminConnectorException) e).getReason())
                .isEqualTo(GarminConnectorException.Reason.INVALID_RESPONSE);
        assertThatThrownBy(() -> GarminRecoveryMapper.map(json("{\"metrics\": {}}"), DAY))
                .isInstanceOf(GarminConnectorException.class);
        assertThatThrownBy(() -> GarminRecoveryMapper.map(null, DAY))
                .isInstanceOf(GarminConnectorException.class);
    }
}

package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import com.runningai.recovery.RecoveryRepository;
import com.runningai.recovery.RecoverySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recovery sync API over the real mapper, storage, schema and REST stack; only the connector client
 * is mocked, so no test talks to Garmin. Fixed clock: 2026-10-02 08:00 in Asia/Seoul.
 */
@SpringBootTest(properties = "running-ai.garmin.recovery-sync.backfill-delay=0s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GarminRecoverySyncApiTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-01T23:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecoveryRepository repository;

    @Autowired
    private GarminRecoverySyncService service;

    @MockitoBean
    private GarminRecoveryClient client;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private JsonNode body(LocalDate date) throws Exception {
        return objectMapper.readTree(GarminRecoveryMapperTest.FULL.replace("2026-10-02", date.toString()));
    }

    private JsonNode onlyRestingHr(LocalDate date, int bpm) throws Exception {
        return objectMapper.readTree("""
                {"date": "%s", "metrics": {
                  "hrv": {"status": "NO_DATA", "data": null},
                  "sleep": {"status": "NO_DATA", "data": null},
                  "restingHeartRate": {"status": "OK", "data": {"value": %d}},
                  "bodyBattery": {"status": "ERROR", "data": null, "error": "GARMIN_UPSTREAM_ERROR"},
                  "stress": {"status": "NO_DATA", "data": null}}}
                """.formatted(date, bpm));
    }

    private ResultActions sync(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/garmin/recovery-sync").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions backfill(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/garmin/recovery-sync/backfill").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void syncStoresTheDayAndReportsAvailableMetrics() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(body(TODAY));

        sync("{\"date\":\"2026-10-02\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-02"))
                .andExpect(jsonPath("$.updated").value(true))
                .andExpect(jsonPath("$.availableMetrics.length()").value(5))
                .andExpect(jsonPath("$.availableMetrics[0]").value("hrv"))
                .andExpect(jsonPath("$.hrvLastNightAvgMs").doesNotExist());

        assertThat(repository.findAll()).singleElement()
                .satisfies(s -> assertThat(s.values().restingHeartRateBpm()).isEqualTo(52));
    }

    @Test
    void reSyncingTheSameDayIsIdempotent() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(body(TODAY));
        sync("{\"date\":\"2026-10-02\"}").andExpect(jsonPath("$.updated").value(true));

        sync("{\"date\":\"2026-10-02\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(false));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void partialDayReportsWhatIsMissingAndKeepsStoredValues() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(body(TODAY), onlyRestingHr(TODAY, 55));
        sync("{\"date\":\"2026-10-02\"}");

        sync("{\"date\":\"2026-10-02\"}")
                .andExpect(jsonPath("$.updated").value(true))
                .andExpect(jsonPath("$.availableMetrics.length()").value(1))
                .andExpect(jsonPath("$.unavailableMetrics.bodyBattery").value("ERROR"))
                .andExpect(jsonPath("$.unavailableMetrics.hrv").value("NO_DATA"));

        RecoverySnapshot stored = repository.findAll().get(0);
        assertThat(stored.values().restingHeartRateBpm()).isEqualTo(55);
        assertThat(stored.values().hrvLastNightAvgMs()).isEqualTo(52.0);
        assertThat(stored.values().bodyBatteryHighest()).isEqualTo(81);
    }

    @Test
    void withoutABodyTheAthletesTodayIsSynced() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(body(TODAY));

        mockMvc.perform(post("/api/v1/garmin/recovery-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-02"));
    }

    @Test
    void aFutureDateIsRefusedWithoutCallingGarmin() throws Exception {
        sync("{\"date\":\"2026-10-03\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECOVERY_DATE_IN_FUTURE"));

        verifyNoInteractions(client);
    }

    @Test
    void aMalformedDateIsABadRequest() throws Exception {
        sync("{\"date\":\"02/10/2026\"}").andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    @Test
    void connectorFailuresMapToTheGarminErrorContractAndStoreNothing() throws Exception {
        doThrow(new GarminConnectorException(Reason.RATE_LIMITED, 429, "slow down")).when(client).fetchDay(TODAY);
        sync("{}").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("GARMIN_RATE_LIMITED"));

        doThrow(new GarminConnectorException(Reason.AUTH_REQUIRED, 401, "login")).when(client).fetchDay(TODAY);
        sync("{}").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GARMIN_AUTH_REQUIRED"));

        doThrow(new GarminConnectorException(Reason.UNAVAILABLE, null, "down")).when(client).fetchDay(TODAY);
        sync("{}").andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GARMIN_CONNECTOR_UNAVAILABLE"));

        assertThat(repository.count()).isZero();
    }

    @Test
    void aBodyForAnotherDayIsABadGatewayAndIsNotStored() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(body(TODAY.minusDays(1)));

        sync("{}").andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GARMIN_UPSTREAM_ERROR"));
        assertThat(repository.count()).isZero();
    }

    @Test
    void backfillFetchesEachDaySequentiallyNewestFirst() throws Exception {
        for (int i = 0; i < 3; i++) {
            LocalDate d = TODAY.minusDays(i);
            when(client.fetchDay(d)).thenReturn(body(d));
        }

        backfill("{\"endDate\":\"2026-10-02\",\"days\":3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-09-30"))
                .andExpect(jsonPath("$.endDate").value("2026-10-02"))
                .andExpect(jsonPath("$.requestedDays").value(3))
                .andExpect(jsonPath("$.fetchedDays").value(3))
                .andExpect(jsonPath("$.updatedDays").value(3))
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.stoppedReason").isEmpty())
                .andExpect(jsonPath("$.days[0].date").value("2026-10-02"))
                .andExpect(jsonPath("$.days[2].date").value("2026-09-30"));

        InOrder order = inOrder(client);
        order.verify(client).fetchDay(TODAY);
        order.verify(client).fetchDay(TODAY.minusDays(1));
        order.verify(client).fetchDay(TODAY.minusDays(2));
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void backfillStopsAtTheFirstRateLimitAndNeverRetries() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(body(TODAY));
        when(client.fetchDay(TODAY.minusDays(1))).thenThrow(new GarminConnectorException(Reason.RATE_LIMITED, 429, "slow down"));

        backfill("{\"days\":28}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetchedDays").value(1))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.stoppedAt").value("2026-10-01"))
                .andExpect(jsonPath("$.stoppedReason").value("RATE_LIMITED"));

        verify(client, times(2)).fetchDay(any());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void backfillWhoseFirstDayFailsReturnsTheConnectorError() throws Exception {
        doThrow(new GarminConnectorException(Reason.AUTH_REQUIRED, 401, "login")).when(client).fetchDay(TODAY);

        backfill("{}").andExpect(status().isUnauthorized());

        verify(client, times(1)).fetchDay(any());
    }

    @Test
    void backfillDefaultsToTwentyEightDaysEndingToday() throws Exception {
        when(client.fetchDay(any())).thenAnswer(inv -> onlyRestingHr(inv.getArgument(0), 50));

        backfill("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-09-05"))
                .andExpect(jsonPath("$.fetchedDays").value(28));

        verify(client, times(28)).fetchDay(any());
    }

    @Test
    void backfillRejectsAnOutOfRangeDayCount() throws Exception {
        backfill("{\"days\":29}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_BACKFILL_DAYS"));
        backfill("{\"days\":0}").andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(client);
    }

    @Test
    void aSecondSyncWhileOneIsRunningIsRejected() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.fetchDay(TODAY)).thenAnswer(inv -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return body(TODAY);
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                service.syncDay(TODAY);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        first.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            sync("{}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("GARMIN_RECOVERY_SYNC_ALREADY_RUNNING"));
            assertThatThrownBy(() -> service.backfill(null, 3)).isInstanceOf(GarminRecoverySyncAlreadyRunningException.class);
        } finally {
            release.countDown();
            first.join(5000);
        }
        assertThat(failure.get()).isNull();
    }
}

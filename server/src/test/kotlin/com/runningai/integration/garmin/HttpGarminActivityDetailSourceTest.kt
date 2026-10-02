package com.runningai.integration.garmin

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount.once
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.ConnectException

/** HTTP contract with the connector's per-activity detail endpoints, against a mock server. */
class HttpGarminActivityDetailSourceTest {

    private val baseUrl = "http://127.0.0.1:8765"
    private val id = "188081596"

    private fun client(
        maxChart: Int = GarminActivityDetailProperties.DEFAULT_SAMPLES_MAX_CHART_SIZE,
    ): Pair<MockRestServiceServer, HttpGarminActivityDetailSource> {
        val builder = RestClient.builder().baseUrl(baseUrl)
        val server = MockRestServiceServer.bindTo(builder).build()
        return server to HttpGarminActivityDetailSource(builder.build(), ObjectMapper(), GarminActivityDetailProperties(maxChart))
    }

    @ParameterizedTest
    @EnumSource(GarminActivityPart::class)
    fun `each part is exactly one GET of its connector path, body returned untouched`(part: GarminActivityPart) {
        val (server, source) = client()
        // only the sample stream is size-capped; every other part is the bare path
        val query = if (part == GarminActivityPart.SAMPLES) "?maxChart=20000" else ""
        server.expect(once(), requestTo("$baseUrl/activities/$id/${part.path}$query")).andExpect(method(GET))
            .andRespond(withSuccess("""{"anything":[1,{"x":null}]}""", MediaType.APPLICATION_JSON))

        val body = source.fetch(part, id)

        assertThat(body.toString()).isEqualTo("""{"anything":[1,{"x":null}]}""")
        server.verify()
    }

    @Test
    fun `the configured maxChart is sent for samples only`() {
        val (server, source) = client(maxChart = 10000)
        server.expect(once(), requestTo("$baseUrl/activities/$id/samples?maxChart=10000"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        server.expect(once(), requestTo("$baseUrl/activities/$id/splits"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        source.fetch(GarminActivityPart.SAMPLES, id)
        source.fetch(GarminActivityPart.SPLITS, id)

        server.verify()
    }

    @Test
    fun `samples are requested at full resolution unless configured otherwise`() {
        val (server, source) = client()
        server.expect(once(), requestTo("$baseUrl/activities/$id/samples?maxChart=20000"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))

        source.fetch(GarminActivityPart.SAMPLES, id)

        assertThat(GarminActivityDetailProperties().samplesMaxChartSize).isEqualTo(20000)
        server.verify()
    }

    @Test
    fun `a request size the connector would refuse fails fast instead of failing every fetch`() {
        // the connector accepts 1..100000 (garmin_connector.client.MAX_CHART_SIZE)
        listOf(0, -1, 100_001).forEach { bad ->
            assertThatThrownBy { GarminActivityDetailProperties(bad) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("samples-max-chart-size")
        }
        assertThat(GarminActivityDetailProperties(1).samplesMaxChartSize).isEqualTo(1)
        assertThat(GarminActivityDetailProperties(100_000).samplesMaxChartSize).isEqualTo(100_000)
    }

    @Test
    fun `json null and arrays are passed through, a scalar body is an invalid response`() {
        val (server, source) = client()
        server.expect(requestTo("$baseUrl/activities/$id/hr-zones")).andRespond(withSuccess("null", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$baseUrl/activities/$id/power-zones")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
        server.expect(requestTo("$baseUrl/activities/$id/detail")).andRespond(withSuccess("\"text\"", MediaType.APPLICATION_JSON))

        assertThat(source.fetch(GarminActivityPart.HR_ZONES, id).isNull).isTrue()
        assertThat(source.fetch(GarminActivityPart.POWER_ZONES, id).isArray).isTrue()
        assertThatThrownBy { source.fetch(GarminActivityPart.DETAIL, id) }
            .isInstanceOfSatisfying(GarminConnectorException::class.java) {
                assertThat(it.reason).isEqualTo(GarminConnectorException.Reason.INVALID_RESPONSE)
            }
    }

    @ParameterizedTest
    @CsvSource(
        "401, GARMIN_AUTH_REQUIRED, AUTH_REQUIRED",
        "403, GARMIN_FORBIDDEN, FORBIDDEN",
        "404, GARMIN_NOT_FOUND, NOT_FOUND",
        "429, GARMIN_RATE_LIMITED, RATE_LIMITED",
        "502, GARMIN_UPSTREAM_ERROR, UPSTREAM_ERROR",
        "500, GARMIN_CONNECTOR_ERROR, CONNECTOR_ERROR",
    )
    fun `connector errors are translated after a single request`(status: Int, code: String, reason: GarminConnectorException.Reason) {
        val (server, source) = client()
        server.expect(once(), requestTo("$baseUrl/activities/$id/splits"))
            .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                .body("""{"code":"$code","message":"m"}"""))

        assertThatThrownBy { source.fetch(GarminActivityPart.SPLITS, id) }
            .isInstanceOfSatisfying(GarminConnectorException::class.java) { assertThat(it.reason).isEqualTo(reason) }
        server.verify()   // exactly one request: no retry
    }

    @Test
    fun `an unreachable connector is UNAVAILABLE`() {
        val (server, source) = client()
        server.expect(once(), requestTo("$baseUrl/activities/$id/samples?maxChart=20000"))
            .andRespond(withException(ConnectException("refused")))

        assertThatThrownBy { source.fetch(GarminActivityPart.SAMPLES, id) }
            .isInstanceOfSatisfying(GarminConnectorException::class.java) {
                assertThat(it.reason).isEqualTo(GarminConnectorException.Reason.UNAVAILABLE)
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "abc", "1.5", "", "../x", "99999999999999999999", "9223372036854775808"])
    fun `an invalid activity id is refused before any request`(bad: String) {
        val (server, source) = client()

        assertThatThrownBy { source.fetch(GarminActivityPart.DETAIL, bad) }.isInstanceOf(IllegalArgumentException::class.java)
        server.verify()
    }
}

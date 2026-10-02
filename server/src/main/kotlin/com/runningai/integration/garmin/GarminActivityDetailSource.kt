package com.runningai.integration.garmin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.runningai.activity.detail.DetailPayloadType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * One per-activity detail part served by the localhost connector (Phase 6H-1A), with the
 * python-garminconnect 0.3.16 method behind it (STATIC_SOURCE_CONFIRMED, not live-verified).
 */
enum class GarminActivityPart(val path: String, val payloadType: DetailPayloadType, val libraryMethod: String) {
    DETAIL("detail", DetailPayloadType.ACTIVITY_DETAIL, "get_activity"),
    SPLITS("splits", DetailPayloadType.SPLITS, "get_activity_splits"),
    HR_ZONES("hr-zones", DetailPayloadType.HR_ZONES, "get_activity_hr_in_timezones"),
    POWER_ZONES("power-zones", DetailPayloadType.POWER_ZONES, "get_activity_power_in_timezones"),
    SAMPLES("samples", DetailPayloadType.ACTIVITY_DETAILS_STREAM, "get_activity_details"),
    ;

    companion object {
        fun of(type: DetailPayloadType): GarminActivityPart? = entries.firstOrNull { it.payloadType == type }
    }
}

/**
 * Transport boundary for the per-activity detail parts. Returns the connector's JSON untouched and
 * interprets nothing; the Garmin detail mappers own the meaning of every field.
 */
interface GarminActivityDetailSource {

    /**
     * @return the part's raw JSON; [NullNode] when the connector returned JSON `null`
     * @throws GarminConnectorException on any connector/Garmin failure (never retried)
     */
    fun fetch(part: GarminActivityPart, garminActivityId: String): JsonNode
}

@ConfigurationProperties(prefix = "running-ai.garmin.detail")
data class GarminActivityDetailProperties(
    /**
     * Garmin `maxChartSize` for the sample stream. Null = the library default (2000). Garmin may
     * down-sample to this many points; the stored samples are whatever Garmin returns, never resampled.
     */
    val samplesMaxChartSize: Int? = null,
)

/** Exactly one connector request per call, no retry; connector errors use the shared translation. */
@Component
class HttpGarminActivityDetailSource(
    @param:Qualifier("garminConnectorRestClient") private val garminConnectorRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: GarminActivityDetailProperties,
) : GarminActivityDetailSource {

    private val log = LoggerFactory.getLogger(HttpGarminActivityDetailSource::class.java)

    override fun fetch(part: GarminActivityPart, garminActivityId: String): JsonNode {
        requireValidGarminActivityId(garminActivityId)
        val body = try {
            garminConnectorRestClient.get()
                .uri { uri ->
                    uri.path("/activities/{id}/{part}")
                    if (part == GarminActivityPart.SAMPLES && properties.samplesMaxChartSize != null) {
                        uri.queryParam("maxChart", properties.samplesMaxChartSize)
                    }
                    uri.build(garminActivityId, part.path)
                }
                .retrieve()
                .body(JsonNode::class.java)
        } catch (e: RestClientResponseException) {
            throw GarminConnectorErrorMapper.toConnectorException(e, objectMapper)
        } catch (e: ResourceAccessException) {
            throw GarminConnectorErrorMapper.unavailable(e)
        }
        val node = body ?: NullNode.instance
        if (!node.isObject && !node.isArray && !node.isNull) {
            throw GarminConnectorException(
                GarminConnectorException.Reason.INVALID_RESPONSE, 200,
                "Garmin connector returned a non-JSON-container body for ${part.path}",
            )
        }
        // The activity id is logged elsewhere at INFO already (ingestion); payload content never is.
        log.info("Garmin connector returned activity part {}", part.path)
        return node
    }
}

private val GARMIN_ACTIVITY_ID = Regex("^[1-9][0-9]{0,18}$")

/** A Garmin activity id is a positive integer; anything else is refused before any request. */
fun requireValidGarminActivityId(id: String) {
    require(GARMIN_ACTIVITY_ID.matches(id) && id.toBigInteger() <= Long.MAX_VALUE.toBigInteger()) {
        "Garmin activity id must be a positive integer"
    }
}

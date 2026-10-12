package com.runningai.injury

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Records and reads the default athlete's self-reported injury/pain status (Phase 7B-2). This
 * never stores a diagnosis: only what the athlete said, and when.
 *
 * No authentication, same convention as the other RunningAI operational APIs: private network only.
 */
@RestController
@RequestMapping("/api/v1/athlete")
class InjuryStateController(
    private val injuryStateService: InjuryStateService,
    private val gate: WorkoutSafetyGateService,
) {

    @PostMapping("/injury-reports")
    fun report(@Valid @RequestBody request: InjuryReportApiRequest): InjuryReportResponse {
        val saved = injuryStateService.record(request.toDomain())
        val decision = gate.currentDecision()
        return InjuryReportResponse.of(saved, decision)
    }

    @GetMapping("/injury-state")
    fun state(): InjuryReportResponse {
        val decision = gate.currentDecision()
        return InjuryReportResponse.of(decision.latestReport, decision)
    }

    @GetMapping("/injury-reports")
    fun history(): List<InjuryReportSummary> = injuryStateService.history().map(InjuryReportSummary::of)
}

data class InjuryReportApiRequest(
    val bodySite: String? = null,
    val side: InjurySide? = null,
    val painPresent: Boolean? = null,
    @field:Min(0) @field:Max(10) val painIntensity: Int? = null,
    val painOnWalking: Boolean? = null,
    val painOnStairs: Boolean? = null,
    val swelling: Boolean? = null,
    val rangeOfMotionLimited: Boolean? = null,
    @field:NotNull val status: InjuryStatus? = null,
    val notes: String? = null,
    val reportedAt: Instant? = null,
) {
    fun toDomain() = InjuryReportRequest(
        bodySite = bodySite,
        side = side,
        painPresent = painPresent,
        painIntensity = painIntensity,
        painOnWalking = painOnWalking,
        painOnStairs = painOnStairs,
        swelling = swelling,
        rangeOfMotionLimited = rangeOfMotionLimited,
        // @field:NotNull on `status` guarantees non-null by the time @Valid lets this method run.
        status = status!!,
        notes = notes,
        reportedAt = reportedAt,
    )
}

data class InjuryReportSummary(
    val id: Long?,
    val bodySite: String?,
    val side: InjurySide?,
    val painPresent: Boolean?,
    val painIntensity: Int?,
    val painOnWalking: Boolean?,
    val painOnStairs: Boolean?,
    val swelling: Boolean?,
    val rangeOfMotionLimited: Boolean?,
    val status: InjuryStatus,
    val notes: String?,
    val reportedAt: Instant,
    val recordedAt: Instant?,
) {
    companion object {
        fun of(r: InjuryReport) = InjuryReportSummary(
            r.id, r.bodySite, r.side, r.painPresent, r.painIntensity, r.painOnWalking, r.painOnStairs,
            r.swelling, r.rangeOfMotionLimited, r.status, r.notes, r.reportedAt, r.recordedAt,
        )
    }
}

data class InjuryReportResponse(
    val report: InjuryReportSummary?,
    val safety: InjurySafetyResponse,
) {
    companion object {
        fun of(report: InjuryReport?, decision: InjurySafetyDecision) =
            InjuryReportResponse(report?.let(InjuryReportSummary::of), InjurySafetyResponse.of(decision))
    }
}

data class InjurySafetyResponse(
    val nonRestTrainingAllowed: Boolean,
    val reasonCode: InjurySafetyReasonCode,
    val message: String,
    val evaluatedAt: Instant,
) {
    companion object {
        fun of(d: InjurySafetyDecision) = InjurySafetyResponse(d.nonRestTrainingAllowed, d.reasonCode, d.message, d.evaluatedAt)
    }
}

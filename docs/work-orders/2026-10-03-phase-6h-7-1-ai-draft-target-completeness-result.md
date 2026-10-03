# Phase 6H-7.1 — AI Draft Target Completeness — Result

Run on the Main PC, 2026-10-03/04 (session spanned the date rollover). Baseline commit `b95a4a0`
("docs: record the Phase 6H-7 result"). Final commits on top: `aeb5d9c` (feat), `eb533c6` (test), and
this documentation commit.

No API key, token, credential or raw real-athlete payload appears in this document, in any committed
file, or in any log referenced here.

1. **Goal**: give every segment of an AI-coach workout draft (warm-up, main, recovery between
   repetitions, cool-down) a device-usable target wherever the athlete's threshold data supports one,
   without the Spring/Kotlin layer ever re-deciding any training content. Concrete evidence this phase
   addresses: the already-approved-and-published Draft #7 rendered `Warm Up 12m` / `Rest 2m` / `Cool
   Down 10m` with no numeric target, only `Main 5m 4:45-5:00/km Pace` had one.
2. **Training decisions never touched**: workout type, duration, repetition count, hard/easy
   selection, and the main interval's pace are entirely the coach's call, unchanged from Phase 6G/6H-7.
   This phase is a lossless-representation problem only.
3. **`HeartRateTarget` change**: `minBpm`/`maxBpm` widened from primitive `int` to boxed `Integer`
   (nullable). The sole production construction site (`RunningIntensityTargetPolicy`) always supplies
   non-null ints, so this is behaviourally safe for the existing deterministic pipeline; an AI draft's
   `HeartRateTarget` now carries only the percent fields, bpm left `null`.
4. **New fields, all additive and nullable** (`WorkoutDraftSegment`): `primaryTargetType`
   (`PrimaryTargetType?`), `heartRatePercentLthrMin`/`Max` (`Int?`), `recovery` (`WorkoutDraftRecovery?`).
   New type `WorkoutDraftRecovery`: `durationMinutes`, `intensity`, `primaryTargetType` (required, not
   nullable - a recovery block never falls back to legacy inference), `description`, pace/%LTHR/
   treadmill fields. Both sit **alongside**, never replacing, the legacy `recoveryDurationMinutes` and
   `heartRateBpmMin/Max` fields.
5. **Exactly one physiological target per segment/recovery block**: pace XOR %LTHR, enforced by
   `WorkoutDraftValidator.validatePrimaryTargetType` - a draft claiming `PACE` while also carrying a
   complete %LTHR pair (or vice versa, or a bare bpm+%LTHR pair with no declared type) fails closed
   (`WorkoutDraftValidationException`), never silently resolved.
6. **Absolute-bpm heart-rate targets remain permanently unpublishable.** No code path converts a
   %LTHR to bpm at publish time (checked by an explicit regression test); the athlete's LTHR at
   approval time may differ from LTHR at publish time, so such a conversion would silently change the
   prescribed effort. `ApprovedWorkoutDraftPublishabilityValidator` still rejects any `heartRateBpmMin/
   Max` from reaching the renderer.
7. **%LTHR guidance reuses the existing policy.** `ClaudeCoachPromptBuilder.TARGET_GUIDANCE` cites
   `RunningIntensityTargetPolicy`'s existing bands (VERY_EASY 65-78%, EASY 75-85% LTHR) as reference
   only; no new zone scheme was invented, and nothing computes or checks against those bands downstream
   - the coach applies its own judgement, same as every other coaching decision in this project.
8. **Passive recovery (`primaryTargetType = NONE`) is legitimate and never force-filled.** Covered by a
   dedicated test (`TrainingContextV2TargetCompletenessEvalTest`) and by the validator: a recovery
   block is validated for shape only (ambiguity, plausibility), never forced through the V2 target-
   completeness rule, which applies to the enclosing segment, not the recovery block.
9. **V2-only hard validation rule** (`WorkoutDraftValidator.validate(draft, context: CoachTrainingContext)`):
   when `context is TrainingContextV2` and the athlete has a measured LTHR or threshold pace, every
   non-REST segment must carry a real pace or %LTHR target; `QUALITATIVE`/no-target fails as
   `"... (V2 target completeness)"`. `TrainingContext` V1 never gets this rule - `ClaudeAiCoach.ask()`
   still calls the plain 3-argument `validate()` for V1, so every existing V1 behaviour and test is
   unchanged. Verified directly by `TrainingContextV2TargetCompletenessEvalTest`'s
   `` `the same drafts remain valid under V1 (no completeness rule applied)` `` test.
10. **Legacy compatibility is structural, not incidental.** Every new field is nullable with no
    non-null default that could alter deserialization of a pre-6H-7.1 JSON blob (`jackson-module-
    kotlin`'s default-parameter-value deserialization treats an absent key exactly as the Kotlin
    default `null`). `WorkoutDraftStructuredWorkoutMapperTest` includes an explicit Draft #7
    legacy-shape regression test (`recoveryDurationMinutes` only, no `recovery` object, no
    `primaryTargetType`) asserting byte-for-byte identical rendered text to before this phase.
11. **DB migration: NONE.** `WorkoutDraft.segments` is stored as one JSONB blob (V6 migration,
    unchanged); confirmed via `git diff b95a4a0..HEAD -- server/src/main/resources/db/migration/`
    showing zero changes. No new Flyway version file exists.
12. **Files changed** (production): `HeartRateTarget.java`, `WorkoutDraft.kt`,
    `WorkoutDraftController.kt`, `WorkoutDraftValidator.kt`, `ClaudeAiCoach.kt`,
    `ClaudeCoachPromptBuilder.kt`, `ClaudeCoachResponseParser.kt`,
    `ApprovedWorkoutDraftPublishabilityValidator.kt`, `WorkoutDraftStructuredWorkoutMapper.kt`.
13. **Test coverage added**:
    - Parser: new-shape parsing (`primaryTargetType`, %LTHR, `recovery`), legacy-shape (Draft
      #7-style) still parses unchanged, and negative cases (unknown enum value, recovery missing its
      own `primaryTargetType`, recovery not a JSON object).
    - Validator: bpm+%LTHR ambiguity, recovery+legacy-recovery ambiguity, `primaryTargetType`
      consistency (claims `PACE`/`HEART_RATE` but missing/extra the other), recovery-block shape
      validation, and a dedicated V2-target-completeness section (LTHR-only, pace-only, both, neither)
      with a local V2 context helper.
    - Mapper/publishability validator: percent-LTHR and targeted-recovery carried losslessly to
      rendered text; updated wording regression (`"...no pace, %LTHR or treadmill-speed target..."`);
      Draft #7 legacy-shape regression test.
    - `DraftPublishApiTest`: a full-target draft (percent-LTHR warm-up/cool-down, pace main, targeted
      %LTHR recovery between 3 repetitions) previews publishable with every segment's target present
      in the rendered text (8 structured steps).
    - New `TrainingContextV2TargetCompletenessEvalTest` (§60): eight scenarios - LTHR+pace available,
      LTHR-only (with a negative case proving the rule actually rejects an incomplete main segment),
      pace-only, no thresholds at all (rule inapplicable), interval with a targeted easy-jog %LTHR
      recovery, deliberate passive (`NONE`) recovery never force-filled, treadmill (speed/incline cue
      alone never satisfies completeness when threshold data exists, with a negative case), and a V1
      sanity check that the completeness rule never applies outside V2.
14. **No hallucinated-threshold / no-absolute-bpm-in-V2 coverage**: the %LTHR-only and pace-only
    scenarios above directly prove the coach never needs (and the validator never accepts) an absolute
    bpm target under V2; the "no thresholds" scenario proves the rule is inert, never inventing a
    threshold that does not exist, when `AthleteThresholds` is `(null, null)`.
15. **H2 tests**: `gradlew clean test` **1188 passed, 0 failed, 0 skipped**, including the new parser,
    validator, mapper/publishability, `DraftPublishApiTest`, and 8-scenario V2 eval tests added this
    phase (no authoritative pre-phase baseline count was captured at `b95a4a0` itself before edits
    began, so no delta is claimed here - the final green totals are what is verified). One interim
    regression caught and fixed before the final run: a
    wording-change regression in `WorkoutDraftStructuredWorkoutMapperTest` (expected substring updated
    to match the publishability validator's new "...%LTHR..." wording) and two test-fixture bugs in
    the new `DraftPublishApiTest` full-target scenario (the WARM_UP and COOL_DOWN segments set
    `heartRatePercentLthrMin/Max` without the now-required explicit `primaryTargetType = HEART_RATE`,
    so the mapper's legacy pace-only inference fell through to `QUALITATIVE` and silently dropped the
    %LTHR target - fixed by setting the type explicitly in the test, matching the documented contract
    that a legacy-inferred draft only ever resolves to `PACE`/qualitative, never `HEART_RATE`).
16. **PostgreSQL tests**: full Spring suite re-run against a throwaway PostgreSQL 17 database
    (`running_ai_test`, Hikari pool capped at 2, `driver-class-name` explicitly overridden to
    `org.postgresql.Driver` alongside the datasource URL - `application-test.yml`'s H2 driver-class-name
    otherwise wins and the whole suite fails to even start a context - dropped after) -
    **1188 passed, 0 failed**, identical to H2.
17. **Python tests**: **134 passed** (connector untouched; `tools/garmin-connector` has no dependency
    on this phase's Kotlin/Java changes - confirmed by `git status` showing zero tracked changes there).
18. **Live Claude CLI validation** (§69-71, Main PC, real `claude` CLI, real stored Main DB data,
    `RUNNING_AI_TRAINING_CONTEXT_VERSION=V2`): worktree commits merged `--ff-only` into the live
    checkout's `main`, rebuilt (`start-running-ai.ps1 -Build`), `POST /api/v1/workout-drafts` with no
    body (today, 2026-10-04). Result: Draft **#8**, status **DRAFT** (never approved, never
    published), `EASY` / 40 min, all three segments (`WARM_UP`, `MAIN`, `COOL_DOWN`) carry a complete
    `primaryTargetType: HEART_RATE` with a real `heartRatePercentLthrMin/Max` pair (65-75, 75-85, 65-72
    respectively) - exactly the completeness gap this phase closes, produced live, not synthetically.
    This particular session had no repeated/interval segment, so the targeted-recovery path was not
    exercised live (it is covered by the synthetic eval scenario and the parser/mapper/validator unit
    tests instead).
19. **External writes during live validation**: 0. Confirmed by grepping the live Spring log
    (`.runtime/logs/spring.out.log`) for any Garmin or Intervals.icu call during the draft generation -
    no matches - and by the Garmin connector's own log showing only the startup `GET /health`, no
    `/activities` or `/recovery` request since the restart.
20. **Draft #7 untouched**: `GET /api/v1/workout-drafts/7` after the live validation still shows
    `status: APPROVED`, `version: 3`, `date: 2026-10-03`, legacy shape intact
    (`recoveryDurationMinutes: 2` on its repeated segment, no `recovery` object) - no UPDATE, no
    re-publish, no re-approval triggered by anything in this phase.
21. **Publishing switches, final values**: `WORKOUT_PUBLISHING_ENABLED=false`,
    `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`, `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`,
    `RUNNINGAI_MCP_ENABLED=false` - unchanged throughout (read from the live `.env` before and after;
    never edited this phase).
22. **No automatic draft approve/publish/Garmin sync** was performed at any point; Draft #8 remains in
    `DRAFT` status and this phase does not approve or publish it.
23. **Architecture doc**: `docs/architecture/ai-draft-targets.md` - the target model (`primaryTargetType`
    contract table), why %LTHR (never bpm) for AI drafts, targeted recovery, the V2-only completeness
    rule, and an explicit "what never changes here" section (no training decision, no recalculation at
    publish time, no renderer change, no DB migration).
24. **Commits** (this session, on top of `b95a4a0`):
    - `aeb5d9c` feat: add device target completeness to AI workout drafts
    - `eb533c6` test: cover AI draft target completeness and legacy compatibility
    - this commit: docs: document AI draft device targets (instruction verbatim, architecture doc,
      this result doc)
25. **Branch / push**: committed on `main-3` (the orca worktree branch), fast-forward-merged into the
    live checkout's `main` for the rebuild/live-validation step above. Not pushed to `origin` - push
    was not requested this phase.
26. **Known limitations**:
    - The live validation session happened to be a continuous `EASY` run with no repeated/interval
      structure, so the targeted-recovery device target was validated live only indirectly (Draft #8
      has no `recovery` object to inspect); the synthetic eval scenario and unit tests are the primary
      coverage for that path until a live interval-session draft is generated.
    - `CoachEvalScenarios`/`CoachEvalScenarioTest` (Phase 6F/6H-7's existing eval corpus) remain V1-only;
      this phase added a separate, smaller V2-specific eval file rather than duplicating the full
      25-scenario V1 corpus under V2, since the V2-specific behaviour under test (target completeness)
      is narrow and orthogonal to the V1 corpus's recovery/pain/load invariants.
    - `scripts/windows/publish-approved-draft-controlled.ps1` (untracked, live-checkout-only) was never
      touched, read for content, reset, or referenced by any new code in this phase.
27. **Next recommended step**: generate a live V2 draft for a session the coach itself chooses to
    structure as repeated work+recovery (e.g. by requesting a quality day when conditions call for one),
    to observe the targeted-recovery path end-to-end against the real Claude CLI; no action should be
    taken on that draft beyond generation/preview without separate explicit approval, per this phase's
    scope.

Completion marker:

```text
PHASE_6H_7_1_AI_DRAFT_TARGET_COMPLETENESS_READY
```

Stopping here. Draft #8's approval, preview, publish, or any Garmin/Intervals write requires separate
explicit approval and is not performed by this phase.

# Phase 6H-7 — TrainingContext V2 & Claude Evidence Integration (result)

Instruction: `docs/work-orders/2026-10-03-phase-6h-7-training-context-v2-instruction.md`.
Run on the Main PC, 2026-10-03.

No real Garmin/Intervals activity id, GPS coordinate, API key or token appears in this document.

## §110 final report

1. **Baseline SHA**: `f70d223`.
2. **Final SHA**: `7c26eea` (plus this result document's commit; see §56 for the full list).
3. **Migration**: `V20__add_workout_draft_context_snapshot.sql` — four nullable columns on
   `workout_draft` (`context_version`, `context_snapshot`, `context_built_at`, `context_sha256`).
   V1-V19 untouched. Applied to H2, a throwaway PostgreSQL 17, and the live Main DB; pre-existing
   `workout_draft` rows (2) kept their content and `NULL` context columns, exactly as specified.
4. **V1 preserved**: yes — `TrainingContext`/`TrainingContextBuilder`/`TrainingDecisionContextService`
   unchanged in behaviour; every pre-existing coach/draft test passes unmodified except for the
   mechanical `CoachTrainingContext` type-widening (`FakeAiCoach`, one Java interop test, two
   `WorkoutDraftApiTest` call sites now cast to `TrainingContext`, `CoachArchitectureTest`'s
   dependency-check list extended) — no V1 test assertion or fixture value changed.
5. **Common context contract**: `CoachTrainingContext` (`date`, `athlete: AthleteThresholds`,
   `constraints: SessionConstraints`), implemented by both `TrainingContext` (V1) and
   `TrainingContextV2`. `AiCoach`, `ClaudeAiCoach`, `ClaudeCoachPromptBuilder` and
   `WorkoutDraftValidator` depend on it, never on a concrete version.
6. **Context selector**: `CoachTrainingContextBuilder` (package `coach`), routing on
   `CoachProperties.contextVersion`; a second `build(date, constraints, version)` overload lets the
   read-only preview endpoint request an explicit version regardless of the configured default.
7. **Source default context version**: `V1` (`@param:DefaultValue("V1")` on `CoachProperties`, and the
   same default in `application.yml`'s placeholder).
8. **Main PC active context version**: **V2** (`RUNNING_AI_TRAINING_CONTEXT_VERSION=V2` in the live
   `.env`, confirmed bound — see §19 below for the live-validation story behind this).
9. **History lookback**: activities 90 days, Intervals fitness 90 days, Garmin recovery 28 days
   (`TrainingContextV2Builder.HISTORY_WINDOW_DAYS` / `FITNESS_WINDOW_DAYS` / `RECOVERY_WINDOW_DAYS`).
10. **Max recent activities**: `running-ai.coach.context-v2.max-recent-activities`, default **8**.
11. **Actual live recent activity count**: **5** (the account has exactly 5 supported activities in
    the 90-day window — all fit under the cap, confirmed by the live preview, §20).
12. **`dataCoverage` live values** (2026-10-03 preview): `historyWindowDays=90`,
    `supportedActivityCount=5`, `analysedActivityCount=5`, `fullSampleActivityCount=5`,
    `intervalsMatchedActivityCount=5`, `fitnessWindowDays=90`, `fitnessDaysAvailable=90`,
    `recoveryWindowDays=28`, `recoveryDaysAvailable=17`, `oldestActivityDate=2026-09-18`,
    `newestActivityDate=2026-09-30` — matches §51's expectation exactly, computed live from the DB,
    never hard-coded.
13. **Recovery coverage**: 17/28 days (consistent with the Phase 6H-6 backfill: this account's
    recovery data only exists from 2026-09-17 onward).
14. **Fitness coverage**: 90/90 days available (full Phase 6H-6 backfill window).
15. **Current CTL source date/age**: `2026-10-03`, `ageDays=0` (today's own fitness day was stored).
16. **Current ATL source date/age**: same day/age as CTL (both come from the same stored row).
17. **D-7 fitness availability**: present (`2026-09-26`).
18. **D-28 fitness availability**: present (`2026-09-05`) in the live preview (the builder's test suite
    separately proves the exact-day-only rule with a case where D-28 is genuinely absent).
19. **Structured interval detection**: `structuredIntervalDetectionAvailable=true`; live preview found
    a real stored interval group and reported `lastStructuredIntervalDate=2026-09-30`,
    `daysSinceLastStructuredInterval=3` — evidence V1 could never produce
    (`qualityDetectionAvailable=false` in the same-date V1 preview).
20. **Last structured interval date availability**: present (see §19); this is exactly the account's
    track-interval activity from earlier phases.
21. **Long-run detection**: uses the existing `running-ai.training.classification.long-run-min-duration`
    (90 min) unchanged; live preview correctly reports `lastLongRunDate=null` because none of the
    account's 5 activities reaches that threshold (the longest is ~46 min) — both V1 and V2 agree on
    this, confirmed in the live Claude draft text ("no long run on record").
22. **RunningAI analysis fields included**: `hrChangePercent`, `speedChangePercent`,
    `speedHrDecouplingPercent`, `cadenceChangePercent`, `lthr90/95/100Seconds`, `lapSpeedCvPercent`,
    `heartRateZonePercent` (map, zones 1-5), `totalZoneSeconds` — all populated from stored
    `ActivityAnalysis` rows, no recompute.
23. **Interval summary fields included**: `workRepCount`, `meanSpeed`, `speedCvPercent`,
    `lastVsFirstSpeedChangePercent`, `hrProgressionBpm`, `recoveryHrDropBpm`,
    `recoveryDurationSeconds` — capped at `max-interval-groups-per-activity` (default 3); no
    individual repetition is ever included (verified by a reflection-based test on the evidence type).
24. **Intervals fields included**: per-activity `trainingLoad`, `intensity`, `ctlAfterActivity`,
    `atlAfterActivity` (Phase 6H-5 terminology); daily `ctl`, `atl`, `derivedForm`, `rampRate`,
    `ctlLoad`, `atlLoad`, plus exact D-7/D-28 trend snapshots.
25. **`candidateTrainingTypes` in V2**: **absent** — confirmed by a reflection test over every V2
    model class (`TrainingContextV2`, `DataCoverageV2`, `TrainingRhythmV2`, `TrainingLoadContextV2`,
    `RecentActivityEvidence`) and by grepping the live serialized snapshot for the string.
26. **Raw sample presence check**: no 1 Hz sample array, no raw Garmin/Intervals JSON anywhere in the
    model or the live snapshot (`CoachContextSerializerTest` + live grep, §29 below).
27. **GPS presence check**: `latitude`/`longitude` absent from the model and the live snapshot.
28. **External ID presence check**: `externalActivityId`/`garminActivityId`/`intervalsActivityId`/
    `externalId`/`externalSource` absent from the model and the live snapshot.
29. **Future-leak tests**: dedicated tests prove an activity started after `D`, a fitness day after
    `D`, and a recovery day after `D` can never appear, while same-day-earlier activities and
    `D`-dated fitness/recovery rows are correctly included (the 90-day boundary itself is also tested
    exactly: day 90 in, day 91 out).
30. **Live V2 serialized byte size**: **7537 bytes** (preview, 2026-10-03) — V1's same-date preview is
    4874 bytes; a live-generated draft's stored snapshot (`context_sha256` prefix `1b26458a44f2…`)
    matches the preview's hash exactly, confirming determinism in production, not only in tests.
31. **Hard byte limit**: 65536 (64 KiB), configurable via
    `running-ai.coach.context-v2.max-snapshot-bytes`; live size is ~11% of the guard.
32. **Deterministic serialization test**: `CoachContextSerializerTest` (same input -> byte-identical
    JSON and hash; one changed metric -> different hash) plus the live cross-check in §30.
33. **`workout_draft` context columns**: `context_version varchar(32)`, `context_snapshot jsonb`,
    `context_built_at timestamptz`, `context_sha256 varchar(64)`, all nullable (verified by a schema
    test asserting nullability on H2/PostgreSQL).
34. **Existing draft migration result**: the 2 pre-6H-7 draft rows kept their status/content;
    `context_version`/`context_snapshot` read back `NULL` (no fabricated backfill).
35. **New draft snapshot persistence**: both `WorkoutDraftContextPersistenceV1Test` and `...V2Test`
    assert a freshly generated draft stores its own `context_version` + a well-formed snapshot/hash,
    live-confirmed by drafts #3 (V1) and #4 (V2) created during this phase.
36. **Context SHA validation**: `[0-9a-f]{64}` format verified; a changed metric produces a different
    hash (unit test); the live draft's stored hash matches the live preview's hash for the same
    DB state (production cross-check).
37. **Revision per-version context test**: `WorkoutDraftContextPersistenceV2Test` generates a draft,
    revises it, and asserts the superseded version's `context_sha256` is unchanged while the revision
    has its own.
38. **Context preview API**: `GET /api/v1/coach/training-context?date=&version=`, DB read-only.
39. **Preview Garmin calls**: **0** (confirmed by log inspection across both live previews — no
    `Garmin*` log line appeared).
40. **Preview Intervals calls**: **0** (same log inspection — no `Intervals read completed` line).
41. **V1 eval result**: `CoachEvalScenarioTest` (20 scenarios, deterministic fake coach) — all pass,
    unmodified.
42. **V2 synthetic eval result**: not implemented as a separate `CoachEvalScenario`-style suite in
    this phase (see §55 limitations) — V2's evidence correctness is instead covered by 22
    `TrainingContextV2BuilderTest` cases plus the live Claude draft in §43, which exercises exactly
    the "recent structured interval + sparse history + full evidence" combination the synthetic
    scenarios in §62-68 were meant to probe, against the real account.
43. **Live Claude eval result**: **one live call under V1, one under V2** (same date, no constraints),
    both via the real `POST /api/v1/workout-drafts` endpoint and the real Claude CLI (`sonnet`).
    - V1 draft (#3): EASY 40 min; recovery assessment accurately summarised all five wearable metrics;
      load assessment correctly described a thin 7-day base; explicitly said quality-session history
      and long-run history were unavailable/absent (V1's honest limitation).
    - V2 draft (#4): EASY 50 min; recovery assessment equally accurate; load assessment explicitly
      cited **"CTL ~13, ATL ~12, form +1"** (Intervals evidence) and **named the actual recent hard
      sessions** ("Sep19 near-max effort, Sep29 treadmill intervals" — RunningAI Analysis evidence V1
      structurally cannot see), used them to recommend rebalancing toward easy volume, and still
      correctly said no long run is on record. No null metric was invented; no source was misattributed.
    Both stayed `DRAFT`; neither was approved or published.
44. **V1/V2 live structural comparison**: V2 added real, usable evidence Claude referenced
    (`dataCoverage`, actual structured-interval history, RunningAI analysis evidence, Intervals
    CTL/ATL/load) while V1's recovery/constraints information was not lost (present in both, same
    `recovery`/`constraints` sections); V2 is larger (7537 vs 4874 bytes) because it carries more
    *useful* evidence, not because of raw data — confirmed by the content, not just the byte count.
45. **Live V2 draft result**: recognised the account's actual structured interval and hard-session
    history (never possible under V1), used CTL/ATL/form as load evidence, honestly reported the
    absence of a long run, did not invent any null metric, respected the (empty) constraints, and
    never confused Garmin/RunningAI/Intervals provenance.
46. **Draft approval status**: neither live-eval draft (#3, #4) was approved.
47. **Workout publish calls**: 0.
48. **Garmin API calls**: 0 (this whole phase; confirmed by log inspection and by construction — V2
    has no Garmin/Intervals dependency, enforced by `CoachArchitectureTest`).
49. **Intervals API calls**: 0.
50. **External workout writes**: 0.
51. **Publishing switches**: `WORKOUT_PUBLISHING_ENABLED=false`, `WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false`,
    `RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false`, `RUNNINGAI_MCP_ENABLED=false` throughout and at the end;
    legacy writer Scheduled Tasks (`RunningAI-TodayWorkout`, `RunningAI-TrainingCommand`,
    `RunningAI-CommandChannel`, `RunningAI-RemoteWakeupScheduler`) confirmed Disabled, untouched.
52. **Historical backfill status**: NOT_RUN this phase (`historical_backfill_run` count unchanged at 1,
    from Phase 6H-6); `activity` count unchanged at 5.
53. **Spring H2 tests**: `gradlew clean test` **1144 passed, 0 failed, 0 skipped**
    (baseline 1101 → +43: 42 new tests plus 1 for the live-discovered config-binding fix, §57).
54. **Spring PostgreSQL tests**: full suite re-run against a throwaway PostgreSQL 17 (Hikari pool
    capped at 2, same reason as Phase 6H-5/6H-6) — **1144 passed, 0 failed**.
55. **Python tests**: **134 passed** (connector untouched).
56. **Commits** (this session, on top of `f70d223`):
    `a7b7c2b` feat: add the TrainingContext V2 model and context snapshot schema ·
    `c0f4518` feat: add the DB-only TrainingContextV2Builder and context router ·
    `2c5a970` feat: wire AiCoach/WorkoutDraftService to CoachTrainingContext and add the preview API ·
    `bb26518` test: cover TrainingContext V2, context persistence and the preview API ·
    `f92a0a9` docs: describe the TrainingContext V2 architecture ·
    `7c26eea` fix: bind RUNNING_AI_TRAINING_CONTEXT_VERSION in application.yml ·
    plus the docs commit containing this result document.
57. **Push**: to `origin/main` after this document was committed.
58. **Working tree**: clean after the final commit; `main = origin/main`.
59. **Known limitations**:
    - **Live-validation catch, fixed in-session**: `application.yml` originally had no placeholder
      binding `running-ai.coach.context-version` to `RUNNING_AI_TRAINING_CONTEXT_VERSION` — every
      automated test that set the property directly (`properties=["running-ai.coach.context-version=V2"]`)
      passed, but the documented environment-variable activation path silently stayed on V1. Live
      validation caught this (the first V2-activation restart still previewed as V1); fixed by adding
      the placeholder and a dedicated regression test that binds via the *exact* documented env var
      name, re-verified live afterward. Root cause for the record: `application.yml`'s relaxed/explicit
      placeholder bindings must always be tested through the literal operator-facing env var name, not
      only through the already-resolved Spring property path.
    - No separate `CoachEvalScenario`-style V2 synthetic suite (§62-68) was added; the live Claude call
      in §43 covers the same ground against real data, but a repeatable synthetic V2 eval suite (sparse
      history, low recovery coverage, provenance-conflict, full-evidence scenarios as fixed fixtures)
      remains useful future work alongside V1's.
    - `recentStructuredQuality` (§37, explicitly optional) was not added — `recentActivities[].
      runningAiAnalysis.intervalGroups` already carries the same information without duplicating the
      activity object, which the instruction allowed as the simpler choice.
    - V2's `trainingRhythm` consecutive-day counters are bounded to the 90-day history window (an
      empty history reports `consecutiveRestDays=90`, not an unbounded count) — a deliberate,
      tested bound, not a gap, but worth stating since V1's own 28-day window never needed one.
60. **Recommended next phase**: Phase 6H-7 leaves both context versions available and V2 active. A
    natural next step is accumulating a handful of real V2 drafts over time (manual, no scheduler) to
    observe coaching quality before considering any further automation; TrainingContext integration
    into a broader scheduling phase (if ever pursued) should keep reading `CoachTrainingContextBuilder`
    rather than a concrete builder, exactly as `WorkoutDraftService` now does.

**PHASE_6H_7_TRAINING_CONTEXT_V2_READY**

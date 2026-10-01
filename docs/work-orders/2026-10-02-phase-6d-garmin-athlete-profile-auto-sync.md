# Phase 6D — Garmin athlete intensity-profile auto-sync (work order)

Verbatim owner instruction: `2026-10-02-phase-6d-garmin-athlete-profile-auto-sync-instruction.md`.

## Pre-check (done before any change)

- Repo: `C:\running-ai-github`. `C:\running-ai` (legacy) is untouched.
- `git branch --show-current` → `main`; `git status` → clean; `HEAD` → `55c143b feat: add MCP workout publish integration`.
- Existing Phase 5C (Intervals publisher)/6A (manual trigger)/6B (publish scheduler)/6C (MCP tool) code paths are not modified except where this phase explicitly adds a new, independent sync path alongside them.

## 6D-0 — Live contract probe (completed before writing any production code)

Ran a one-off read-only script against the owner's real Garmin account, token-store auth only
(`~/.garminconnect`, no credentials in code, no token values printed), using the already-installed
`garminconnect==0.3.16` in `tools/garmin-connector/.venv`:

```python
garmin = Garmin()
garmin.login("~/.garminconnect")
result = garmin.get_lactate_threshold()
```

Real (sanitized) response shape:

```json
{
  "speed_and_heart_rate": {
    "userProfilePK": "<redacted>",
    "version": 1790789026031,
    "calendarDate": "2026-10-01T02:23:45.995",
    "sequence": 1790789026031,
    "speed": 0.34444348,
    "heartRate": 180,
    "heartRateCycling": null
  },
  "power": {
    "userProfilePk": "<redacted>",
    "calendarDate": "2026-10-01T00:38:39.0",
    "origin": "power",
    "sport": "RUNNING",
    "functionalThresholdPower": 349,
    "weight": 72.0,
    "powerToWeight": 4.85,
    "ftpCreateTime": "2026-10-01T00:38:39.0",
    "weightCreateTime": "2026-09-17T16:44:48.92",
    "isStale": false
  }
}
```

Findings (answering the required checklist):

1. **LTHR bpm field**: `speed_and_heart_rate.heartRate` (int, bpm). Live value `180` — matches the
   owner's manually-entered baseline exactly.
2. **LT speed field**: `speed_and_heart_rate.speed` (float). No LT *pace* field exists; only speed.
3. **Unit**: **not documented** by Garmin or by `python-garminconnect` (source inspected directly;
   no docstring, no comment, no upstream issue/PR thread states it). Empirically derived from the
   live value: raw `0.34444348`. Interpreting it as plain m/s gives 48:23/km (physiologically
   impossible as a running LT pace). Multiplying by 10 gives `3.4444348 m/s` → `1000/3.4444348 =
   290.3 sec/km` = **4:50.3/km**, matching the owner's independently known real LT pace
   (`290 sec/km`, `4:50/km`) almost exactly (0.1% difference, consistent with Garmin's internal
   rounding). **Conclusion: raw `speed` is in units of `0.1 m/s`; multiply by 10 to get m/s.** This
   is an empirical finding, not an official contract — documented in code with this derivation.
4. **Timestamp/date field**: `calendarDate` exists on both `speed_and_heart_rate` and `power`
   (string, no zone designator, like Garmin's other endpoints). Not used in Phase 6D (see "minimal
   schema" decision below).
5. **Null/empty state**: when a sub-value is absent Garmin leaves the corresponding dict key `null`
   (seen directly: `heartRateCycling: null`); the whole `speed_and_heart_rate`/`power` dict is always
   present with this account. The mapper must still treat a missing/non-numeric `heartRate`/`speed`
   as "field absent", not crash.
6. **Nested structure**: yes — two top-level keys (`speed_and_heart_rate`, `power`), not a flat
   object. `power` (cycling/running functional threshold power) is out of scope for Phase 6D
   (running LTHR/pace only; see Non-goals) and is not read or forwarded past the connector.

No live payload is committed anywhere; the JSON above has the one identifying field (`userProfilePK`,
twice) redacted, and the fixtures used in automated tests (Python `conftest.py`, Spring Java tests)
use entirely synthetic numbers, consistent with the project's existing `synthetic_item()` convention.

## 6D-1 — Python connector (`tools/garmin-connector`)

- `garmin_connector/client.py`: `GarminGateway.lactate_threshold()` (wraps
  `self._garmin.get_lactate_threshold()`, translates exceptions via the existing `translate()`,
  validates the result is a `dict`); `CachedGatewayProvider.lactate_threshold()` forwards through the
  cached gateway with the same auth-failure cache-drop behaviour as `recent_activities`.
- `garmin_connector/api.py`: `create_app(fetch_activities, fetch_lactate_threshold)` gains
  `GET /lactate-threshold` returning the raw dict untouched (no renaming/normalisation — that is
  Spring's job), reusing the same `ConnectorError` exception-handler contract as `/activities`.
- `garmin_connector/__main__.py`: `serve` wires `provider.lactate_threshold` as the second
  `create_app` argument.
- No change to `/health`, `/activities`, the error contract, or the CLI's other subcommands.

## 6D-2 — Spring (`com.runningai.integration.garmin`, `com.runningai.athlete`)

New transport + mapping (mirrors `GarminActivitySource`/`HttpGarminActivitySource`/`GarminActivityMapper`):

- `GarminLactateThresholdSource` (interface) → `HttpGarminLactateThresholdSource` (`GET
  /lactate-threshold` on the same `garminConnectorRestClient`, same connector-error mapping as
  `HttpGarminActivitySource`, factored into a shared `GarminConnectorErrorMapper` to avoid
  duplicating the status→`Reason` switch).
- `GarminSpeedConverter` (pure, package-visible): `rawSpeedToSecondsPerKilometer(Double rawSpeed)` —
  documents the `×10` derivation above, returns `null` (not an exception) for `null`/non-finite/`<=0`
  input or an overflowing result; otherwise `(int) Math.round(1000.0 / (rawSpeed * 10))`.
- `GarminLactateThresholdMapper` (pure): `map(JsonNode raw) -> GarminLactateThresholdSnapshot`; reads
  `speed_and_heart_rate.heartRate` (positive finite int, else `null`) and
  `speed_and_heart_rate.speed` (converted via `GarminSpeedConverter`, else `null`). `power` is
  ignored entirely (Non-goal).
- `GarminLactateThresholdSnapshot` (record): `Integer lactateThresholdHeartRateBpm, Integer
  lactateThresholdPaceSecondsPerKm` — the **minimal** domain shape asked for; no `measuredAt`/raw
  provenance field is added (avoiding the unnecessary schema expansion the instruction explicitly
  allows skipping), since nothing in this phase needs it and `athlete_intensity_profile` already has
  no history.
- `GarminAthleteProfileSyncService`: fetches raw JSON, maps it, logs INFO/WARN per the observability
  section, and calls a **new** merge operation (not the existing PUT) on `AthleteIntensityProfileService`.

Storage/merge policy — new `AthleteIntensityProfileService.mergeGarminSnapshot(Integer bpm, Integer
paceSecPerKm)` (`athlete` package, alongside the existing `replaceDefaultProfile` PUT, which is
**unchanged**: null still means "clear"). The merge method:

1. Reads the current row (if any).
2. Resolves each metric independently: a non-null incoming value replaces it; a `null` incoming
   value **keeps the existing value** (never clears it — this is the key difference from PUT).
3. If neither resolved value differs from what's stored, returns `updated=false` and **does not
   call `save`/touch the row at all** (rules 4 and 7/8).
4. Otherwise saves (create on first row, update in place otherwise) and returns which metric(s)
   changed.
5. A Garmin connector failure is thrown by `GarminLactateThresholdSource.fetchLatest()` **before**
   this method is ever called, so a failed fetch never reaches the database (rule 5) — the last
   saved profile is left exactly as-is (the "stale fallback" the instruction asks for, since
   `WorkoutIntensityTargetService` simply re-reads whatever is currently stored on every call).

`WorkoutIntensityTargetService` is **not modified** — it already re-reads
`AthleteIntensityProfileService.getDefaultProfile()` on every call with no caching, so a profile sync
is picked up by the very next prescription/target/publish call with zero code change there.

### API

`POST /api/v1/garmin/profile-sync` (`GarminProfileSyncController`, no auth — same convention as
`POST /api/v1/garmin/sync` and `POST /api/v1/workout-publish`, private network only) →
`GarminAthleteProfileSyncService.sync()` → `GarminProfileSyncResponse{updated,
lactateThresholdHeartRateBpm, lactateThresholdPaceSecondsPerKm}` (current profile state after the
attempt; never the raw Garmin payload). Connector failures reuse the existing
`GarminSyncExceptionHandler` (`assignableTypes` extended to include the new controller) — same
401/403/429/502/503 mapping already used by `/api/v1/garmin/sync`.

### Scheduler (independent, default OFF)

Per the instruction's own priority order: combining profile sync into the existing
`GarminSyncScheduler` (activity incremental sync) was rejected — it is a different Garmin endpoint
with no relationship to the activity high-water-mark checkpoint, and bundling them would remove the
ability to enable/disable each independently. A new, independent `GarminProfileSyncScheduler` +
`GarminProfileSyncSchedulingConfig` + `GarminProfileSyncProperties` mirrors
`WorkoutPublishingScheduler`'s cron/zone pattern exactly:

- `running-ai.garmin.profile-sync.scheduler.enabled` / env `GARMIN_PROFILE_SYNC_ENABLED`, default
  **false**.
- `cron` default `0 30 4 * * *`, `zone` default `Asia/Seoul` (same zone convention as the Garmin
  activity scheduler and the workout-publishing scheduler) — runs 30 minutes before the
  workout-publishing scheduler's default 05:00, matching the recommended operational order
  (profile sync → DB → workout publish), but the two schedulers remain entirely independent
  components with no shared code path, lock, or dependency; the workout-publish path never calls
  Garmin and never fails because of it.
- Calls only `GarminAthleteProfileSyncService.sync()`; any exception is logged and ends the tick, no
  retry, nothing escapes the scheduler thread — identical shape to `GarminSyncScheduler`/
  `WorkoutPublishingScheduler`.

### Validation

- `bpm > 0`, finite — rejected by `GarminLactateThresholdMapper` (treated as absent) and still
  enforced by `AthleteIntensityProfile`'s existing constructor/`update` guard (`> 0`) as a second
  line of defence.
- `paceSecondsPerKm > 0`, finite — enforced by `GarminSpeedConverter` (returns `null` otherwise) and
  the same entity-level guard.
- No additional physiological bounds (e.g. "LTHR must be between 120–220") are hard-coded — the
  instruction explicitly asks not to invent sport-science constants without justification, and none
  is needed for this phase.

## Non-goals (unchanged from the instruction)

HRV, Body Battery, Training Readiness, Training Status, VO2Max, race prediction, direct Garmin
workout upload, any MCP change, ChatGPT↔MCP wiring, enabling the workout-publishing scheduler,
large Garmin ingestion refactors, Spring Boot 4 migration.

## Result doc

`2026-10-02-phase-6d-garmin-athlete-profile-auto-sync-result.md`, written after implementation,
full regression, and live validation.

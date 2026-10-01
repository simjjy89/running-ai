# Phase 6D — Garmin athlete intensity-profile auto-sync (result)

Baseline commit `55c143b feat: add MCP workout publish integration`. Branch `main`.

## What was implemented

Garmin Connect → `garmin-connector` → Spring Garmin profile sync → `athlete_intensity_profile` →
`WorkoutIntensityTargetService`, exactly as scoped in the work order. See
`2026-10-02-phase-6d-garmin-athlete-profile-auto-sync.md` for the design and the 6D-0 live-contract
derivation (speed-unit conversion rule).

## 1. Changed/added files

**Python connector** (`tools/garmin-connector/`)
- Modified: `garmin_connector/client.py` (`GarminGateway.lactate_threshold()`,
  `CachedGatewayProvider.lactate_threshold()`), `garmin_connector/api.py` (`GET /lactate-threshold`,
  `create_app` now takes a second fetcher), `garmin_connector/__main__.py` (wires it in `serve`).
- Modified tests: `tests/conftest.py` (`FakeGarmin.get_lactate_threshold`,
  `synthetic_lactate_threshold()`), `tests/test_client.py`, `tests/test_api.py`.

**Spring** (`server/src/main/java/com/runningai/`)
- New: `integration/garmin/GarminLactateThresholdSource.java`,
  `HttpGarminLactateThresholdSource.java`, `GarminConnectorErrorMapper.java` (factored out of
  `HttpGarminActivitySource`), `GarminSpeedConverter.java`, `GarminLactateThresholdMapper.java`,
  `GarminLactateThresholdSnapshot.java`, `GarminAthleteProfileSyncService.java`,
  `GarminProfileSyncResponse.java`, `GarminProfileSyncController.java`,
  `GarminProfileSyncProperties.java`, `GarminProfileSyncScheduler.java`,
  `GarminProfileSyncSchedulingConfig.java`; `athlete/GarminProfileMergeResult.java`.
- Modified: `athlete/AthleteIntensityProfileService.java` (`mergeGarminSnapshot`, PUT semantics
  unchanged), `integration/garmin/HttpGarminActivitySource.java` (now uses the shared error mapper,
  no behaviour change), `integration/garmin/GarminSyncExceptionHandler.java` (`assignableTypes`
  extended to the new controller), `application.yml` (new `running-ai.garmin.profile-sync` block).
- New tests: `GarminSpeedConverterTest`, `GarminLactateThresholdMapperTest`,
  `HttpGarminLactateThresholdSourceTest`, `GarminAthleteProfileSyncServiceTest`,
  `GarminProfileSyncApiTest`, `GarminProfileSyncSchedulerTest`,
  `GarminProfileSyncSchedulerDisabledTest`, `GarminProfileSyncSchedulerWiringTest`; extended
  `AthleteIntensityProfileServiceTest` (merge cases) and `WorkoutIntensityTargetServiceTest`
  (immediate pickup via Garmin merge).
- Docs: this file, the work order, and the verbatim instruction file.

`WorkoutIntensityTargetService`, `WorkoutPublishApplicationService`, `WorkoutPublishingScheduler`,
the MCP tool, and every Phase 5C/6A/6B/6C file are **unchanged**.

## 2. Real Garmin contract (Phase 6D-0, live-probed)

`Garmin.get_lactate_threshold()` (python-garminconnect 0.3.16, `latest=True`, token-store auth):

```json
{
  "speed_and_heart_rate": {
    "userProfilePK": "<redacted>", "version": 1790789026031,
    "calendarDate": "2026-10-01T02:23:45.995", "sequence": 1790789026031,
    "speed": 0.34444348, "heartRate": 180, "heartRateCycling": null
  },
  "power": { "functionalThresholdPower": 349, "sport": "RUNNING", "...": "..." }
}
```

- LTHR: `speed_and_heart_rate.heartRate` (bpm, int). Live value `180`, matching the owner's manual
  baseline exactly.
- No LT *pace* field exists — only `speed_and_heart_rate.speed` (float, raw unit undocumented).
- `calendarDate` exists but is not used (no `measuredAt`/provenance field was added — avoiding the
  unneeded schema expansion the work order explicitly allows skipping; nothing in this phase needs
  history).
- Both sub-objects are always present on this account; a missing metric shows as `null` in place
  (observed directly for `heartRateCycling`).
- `power` (cycling/running functional threshold power) is nested but entirely out of scope and
  never read past the mapper.
- No payload or identifier was committed; fixtures/tests use synthetic values only.

## 3. LT speed → pace conversion rule

Unit is **not documented** anywhere (Garmin, `python-garminconnect` source, or its GitHub
issues/PRs — checked directly). Empirically derived: raw `0.34444348` as plain m/s gives an
impossible 48:23/km; raw × 10 gives `3.4444348 m/s` → `290.3 sec/km` (4:50.3/km), matching the
owner's independently known real LT pace (290 sec/km) to within 0.1%.

**Rule**: raw Garmin speed is in units of **0.1 m/s**. `GarminSpeedConverter.rawSpeedToSecondsPerKilometer`:
`secondsPerKm = round(1000 / (rawSpeed * 10))`, implemented as a pure function that returns `null`
(never throws) for `null`, non-finite, non-positive, or overflowing input/output. Unit-tested
against the real live value and edge cases (zero, negative, NaN, infinity, tiny-value overflow).

## 4. Storage / merge policy

`AthleteIntensityProfileService.replaceDefaultProfile` (manual PUT, `null` = clear) is **unchanged**
— used verbatim as the documented fallback. New `mergeGarminSnapshot(Integer bpm, Integer
paceSecPerKm)`:

- A non-null input metric always overwrites the stored value with Garmin's latest.
- A `null` input metric (missing/malformed at Garmin) leaves the stored value **untouched** — never
  clears it.
- No database write at all when neither resolved value differs from what's currently stored (both
  inputs `null`, or both equal current values) — verified idempotent in both automated tests and
  live validation (repeated sync → `updated:false`, same values).
- A Garmin connector failure is thrown before the merge is ever called, so the profile is left
  exactly as-is (stale fallback); `WorkoutIntensityTargetService` re-reads the current profile on
  every call with no change needed there.
- No manufactured physiological bounds (e.g. "LTHR must be 120–220") — only `>0`/finite, per the
  instruction's own guidance not to hard-code unjustified sport-science constants.

API: `POST /api/v1/garmin/profile-sync` → `{"updated": bool, "lactateThresholdHeartRateBpm": Int?,
"lactateThresholdPaceSecondsPerKm": Int?}` (current state after the attempt; never the raw Garmin
payload). No auth, same convention as the existing `/api/v1/garmin/sync` and
`/api/v1/workout-publish`. Scheduler: independent `GarminProfileSyncScheduler`
(`running-ai.garmin.profile-sync.scheduler.enabled` / env `GARMIN_PROFILE_SYNC_ENABLED`, default
**false**, cron `0 30 4 * * *` Asia/Seoul by default) — deliberately not combined with
`GarminSyncScheduler` (different endpoint/checkpoint) or `WorkoutPublishingScheduler` (workout
publishing never depends on, and never fails because of, Garmin profile availability).

## 5. Tests

| Suite | Count | Result |
|---|---|---|
| Python connector (`pytest`) | 50 | all pass (16 new: `/lactate-threshold` + client wrapper tests) |
| Spring full suite (`gradlew clean test`, JDK 21) | 560 | all pass, 0 failed, 0 skipped |

New Spring tests cover all 14 required cases: first insert, both-updated, unchanged/no-write,
HR-only (pace preserved), pace-only (HR preserved), both-missing (no DB change), malformed HR
ignored, malformed pace ignored, connector failure preserves existing profile, speed→sec/km
conversion (pure + live value), scheduler default disabled, scheduler cron/zone wiring, existing
manual PUT semantics unchanged (pre-existing tests untouched and still green), and
`WorkoutIntensityTargetService` picking up a Garmin merge on the very next call. No automated test
calls the real Garmin or Intervals API.

## 6. Live validation

Connector (`python -m garmin_connector serve`, real token store) + Spring (JDK 21, real PostgreSQL
17 via the existing `docker compose` container, `running-ai.garmin.connector.base-url` default) run
on a scratch port (`8099`) so the owner's existing always-on instance on 8080 was never touched or
restarted:

1. `GET /lactate-threshold` on the live connector → identical to the 6D-0 probe (`heartRate: 180`,
   `speed: 0.34444348`).
2. `POST /api/v1/garmin/profile-sync` → `{"updated":false,"lactateThresholdHeartRateBpm":180,"lactateThresholdPaceSecondsPerKm":290}`
   — Garmin's current real LTHR/pace already equal the owner's manually-entered baseline, so the
   expected (and correct) outcome is "no change", not a write.
3. `GET /api/v1/athlete/intensity-profile` → unchanged, `180` / `290`, matching Garmin exactly.
4. Repeated `POST /api/v1/garmin/profile-sync` → again `updated:false` with the same values
   (idempotency confirmed live, not just in tests).
5. `GET /api/v1/workout-intensity-targets` → `targetAvailability: FULL`, `primaryTargetType: PACE`
   on every running segment, pace/HR/treadmill targets all populated from the live-synced profile.
6. Confirmed `POST /api/v1/workout-publish` still refused (master switch default off, unchanged) —
   **no new Intervals workout was published**, as scoped.

Test server and connector processes were stopped afterward; the owner's pre-existing service on
port 8080 was left running throughout, untouched.

## 7. Configuration / environment variables

- `GARMIN_PROFILE_SYNC_ENABLED` (→ `running-ai.garmin.profile-sync.scheduler.enabled`) — default
  **false**.
- `GARMIN_PROFILE_SYNC_SCHEDULER_CRON` — default `0 30 4 * * *`.
- `GARMIN_PROFILE_SYNC_SCHEDULER_ZONE` — default `Asia/Seoul`.
- No new secrets; the connector keeps using its existing token store only.

## 8. Commit

See the commit this result doc is part of (branch `main`). Message summarizes the Phase 6D change;
the commit SHA is reported alongside this summary after it is created.

## 9. Next phase recommendation

With the profile now self-updating, the natural next step is **enabling the operational chain on
this PC in order**: (a) flip `GARMIN_PROFILE_SYNC_ENABLED=true` and watch one real scheduled run,
(b) only after that is stable, revisit the still-pending Phase 5C-5 legacy main-PC retirement and
the Phase 6A/6B master/scheduler switches for workout publishing — both remain intentionally off
and are unaffected by this phase. Candidate follow-ups beyond that (not started, no code exists):
interval/repeat and QUALITY workout structure, a cycling threshold profile, and the Non-goals listed
in the work order (HRV, Body Battery, Training Readiness/Status, VO2Max, race prediction).

## Limitations

- One Garmin account, one live profile state (Garmin's current value happened to equal the manual
  baseline, so a live "value actually changes from X to Y" sync was not observed — only the
  idempotent "no change" path and the mapped conversion were confirmed against a live payload; the
  "changed value" path is covered by Spring unit/API tests with synthetic before/after values, not
  by a live Garmin value change).
- The raw Garmin `speed` unit is an empirical derivation (Phase 6D-0), not an official Garmin
  contract; if Garmin ever changes it, only `GarminSpeedConverter` needs to change.
- No history/provenance is stored for a Garmin-sourced value (by design, see work order); a future
  phase that wants "when was this last confirmed by Garmin" would need a schema change.

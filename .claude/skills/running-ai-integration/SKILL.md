---
name: running-ai-integration
description: Rules for RunningAI external integrations — Garmin, Intervals.icu, other external APIs, ingestion pipelines, synchronization, retries, credentials and external payload mapping. Use whenever a change touches com.runningai.integration.*, GarminActivityMapper / GarminActivityIngestionService, fixtures under server/src/test/resources/fixtures, or introduces any HTTP client, credential or scheduler.
---

# RunningAI integration rules

Follow `running-ai-dev` for the workflow and `running-ai-database` for persistence.

## Principles

- **Network layer ≠ ingestion/domain layer.** A client fetches and returns raw
  `JsonNode`s; an ingestion service stores and normalises them; domain/application code
  never depends on an HTTP client. Future clients (Garmin, Intervals.icu) plug into the
  existing ingestion services instead of changing them.
- **Preserve the external payload before normalising** (raw-first). A mapping failure
  must leave the raw row committed; a network failure must never corrupt normalised data.
- External ids never become internal PKs; identity is `external_source + external_id`.
- Credentials, tokens and session data come from the environment (`GARMIN_USERNAME`,
  `GARMIN_PASSWORD`, `INTERVALS_API_KEY` are reserved, unused) and never appear in the
  repo, in fixtures, or in logs. Never log a full raw payload.
- Do not guess external API contracts. Investigate the owner's existing PowerShell /
  Node.js implementation (main PC) or real sample payloads before writing a client or
  changing a mapper. Fixtures are synthetic and must not contain real user data or GPS.
- Retries, locks or batch frameworks are added only when a concrete need exists; the DB
  unique constraints are the last line of defence against concurrent ingestion.

## Garmin — current state (Phase 3C-1)

Implemented in `com.runningai.integration.garmin`:
`GarminActivityMapper` (pure: `extractActivityId`, `parse`, `map`),
`GarminActivityPayload`, `GarminActivityMappingException` (+ `Reason`/code),
`GarminActivityIngestionService` (`ingest(JsonNode[, fetchedAt])`, `reprocess(id)`),
`GarminIngestionResult`; transport boundary `GarminActivitySource` (interface) →
`HttpGarminActivitySource` (`RestClient`, `GET {base-url}/activities?start=S&limit=N`,
one request, no retry, errors → `GarminConnectorException` + `Reason`), config
`GarminConnectorProperties` (`running-ai.garmin.connector.base-url`, timeouts; **no
credentials**).

Two sync entry points sit on top of the same ingestion core — pick deliberately, they
are not interchangeable:
- `GarminSyncService.syncRecent(limit)` → `GarminSyncResult(fetched, created, updated,
  skipped, failed)`: fetches the last `limit` activities every time (`start=0`), no
  checkpoint. Kept for manual/ad hoc use; not what a scheduler should call.
- `GarminIncrementalSyncService.syncIncremental()` → `GarminIncrementalSyncResult
  (fetched, created, updated, skipped, failed, pagesFetched, checkpointAdvanced,
  highWaterStartedAt)`: the high-water-mark + overlap-window sync described below. This
  is the one a future scheduler/trigger (Phase 3C-2+) should call.

Fixtures: `server/src/test/resources/fixtures/garmin/` (ad hoc ones for incremental
sync's timestamp-boundary tests: `GarminIncrementalSyncFixtures`, same test package).

Python connector `tools/garmin-connector/` (`python -m garmin_connector
login|status|serve|activities`): owns Garmin auth/MFA/token store (`~/.garminconnect`),
binds 127.0.0.1 only, `GET /activities?start=S&limit=N` (`start` optional, default 0,
`limit` optional, default 20) returns raw items as a JSON array, error contract
`{code,message}` with `GARMIN_AUTH_REQUIRED|FORBIDDEN|RATE_LIMITED|UPSTREAM_ERROR|CONNECTOR_ERROR`.
It never touches the database and never normalises. No HTTP login endpoint.

**Operational API (Phase 3C-2):** `POST /api/v1/garmin/sync` runs one
`GarminIncrementalSyncService.syncIncremental()` behind an in-JVM single-flight guard
(`GarminSyncOperationService`, `tryLock`; a concurrent call gets 409
`GARMIN_SYNC_ALREADY_RUNNING`; released in `finally`); `GET /api/v1/garmin/sync/status`
reads `garmin_sync_state` only (never the connector). Connector failures map to
401/403/429/503/502 via `GarminSyncExceptionHandler`. `checkpointAdvanced` means the
high-water mark moved forward (or was created), not merely that
`lastSuccessfulSyncAt` was refreshed. No authentication on these endpoints.

**Scheduler (Phase 3C-3):** `GarminSyncScheduler` (`@Scheduled` fixedDelay, `running-ai.garmin.scheduler.enabled`
default **false**, `fixed-delay` 1h, `initial-delay` 1m; env `RUNNING_AI_GARMIN_SCHEDULER_*`) only calls
`GarminSyncOperationService.runSync()`, so it shares the single-flight guard with the API. One attempt per tick, no
retry inside a tick (429/auth/connector-down just wait for the next tick), already-running is a quiet skip,
nothing escapes the scheduler thread, logs carry counts/reason codes only. Tests must never enable it against a real connector.

**Not implemented — do not assume it exists:** retry/backoff growth, scheduler history,
connector process supervision, FIT/TCX download/storage, typed splits / split summaries / weather / gear /
exercise sets, any scheduled or batch detail collection, historical backfill beyond `max-pages`.

**Detailed activity (Phase 6H-1A, contract LIVE_VERIFIED in Phase 6H-1B):** connector
`GET /activities/{id}/detail|splits|hr-zones|power-zones|samples` → `GarminActivityDetailSource` →
`GarminActivityDetailIngestionService` (raw `activity_raw_payload` first, then `GarminLapMapper` /
`GarminZoneMapper` / `GarminSampleMapper`, per-part status in `activity_detail_collection`). The connector
creates `Garmin(retry_attempts=0)` — python-garminconnect 0.3.16 otherwise retries 5xx 3× by itself.
Every mapper key was confirmed against four real activities; a mapper change is applied with `/reprocess`
from the stored raw payloads, never by re-fetching. Live rules that must not be broken:
**`metricsIndex` is valid only inside its own payload** (one device produced four different layouts —
`directTimestamp` at index 7/5/9/2), so samples are always resolved through that payload's `metricDescriptors`;
`directTimestamp` is a JSON **float** of epoch milliseconds; descriptor `unit.factor` is **not** a divisor
(values already carry the stated unit — never scale them); lap `lapIndex` is **1-based** and the interval
structure lives in `intensityType` + `wktStepIndex`, now mapped to the first-class lap columns
`intensity_type` / `workout_index` / `workout_step_index` (V15) and still kept in `extra_metrics`
(a lap is **not** 1:1 with a workout step — group by `wktStepIndex`; `intensity_type` is a free string so an
unseen Garmin value widens the data instead of breaking ingestion); zones always arrive as 5 entries with **no upper bound** (never derive one); power zones of an
activity without a power meter are `[]` → part status `EMPTY`, never a failure; `/samples` **down-samples** to the requested
`maxChartSize`, so Phase 6H-1C made `running-ai.garmin.detail.samples-max-chart-size` default to **20000** and always
send it (sample part only; range 1..100000, outside it startup fails). That request is never treated as proof: each
collection records `requested_max_chart_size` / `source_metrics_count` / `source_total_metrics_count` /
`sample_completeness` (FULL / DOWNSAMPLED / UNKNOWN) in `activity_detail_collection` (V14), judged from the response
(`totalMetricsCount` is the authority; a missing count, or a payload whose `metricsCount` disagrees with its entries,
is UNKNOWN and never FULL). Never re-request a DOWNSAMPLED stream at another size and never lower `maxChartSize` to
retry a 429; `reprocess` recomputes completeness from stored raw with no Garmin call and carries
`requested_max_chart_size` forward only when it was actually recorded. FIT decision: **A — API detail suffices**; `download_activity`
returns a ZIP holding one `.fit`, archival-only, not implemented.
Contract: `docs/architecture/garmin-detailed-activity-contract-static.md` (live facts in its §L1–§L7);
run record: `docs/work-orders/2026-10-02-phase-6h-1b-garmin-detailed-live-contract-result.md`. Phase 3B-3
(`docs/work-orders/2026-09-29-garmin-live-e2e-validation.md`) ran the full
Connector → Spring → PostgreSQL path against one real Garmin activity twice (live
login, live fetch, live sync, live idempotency all passed) — the main contract
fields below are **CONFIRMED_LIVE**. Phase 3C-1
(`docs/work-orders/2026-09-30-garmin-incremental-sync.md`) additionally live-validated
pagination (`start` offset), bootstrap, and a second incremental sync's overlap +
idempotency against the real account.

### Incremental sync (Phase 3C-1): high-water mark + overlap window

`garmin_sync_state` (one row per athlete, `GarminSyncState`/`GarminSyncStateRepository`/
`GarminSyncStateService`, migration `V4__create_garmin_sync_state.sql`) holds:
- `high_water_started_at`: the newest activity start time successfully processed so far.
  Only ever moves forward (`GarminSyncState.advance`); never advances on a failed run.
- `last_successful_sync_at`: when an incremental sync last completed, updated on every
  successful run even if the high-water mark itself did not move.

Config (`running-ai.garmin.sync.*`, `GarminIncrementalSyncProperties`, all optional):
`page-size` (default 50), `overlap` (default `7d`, a `Duration`), `max-pages` (default 10).

Algorithm (`GarminIncrementalSyncService.syncIncremental()`):
```text
no GarminSyncState  -> bootstrap: exactly one page (page-size items), no cutoff,
                       max-pages does not apply
GarminSyncState     -> cutoff = highWaterStartedAt - overlap
                       page newest -> oldest (start=0,page-size ; start=page-size,page-size ; ...)
                       until: a page's oldest parseable startTime <= cutoff, OR a page
                       returns fewer than page-size items (Garmin history exhausted), OR
                       max-pages is exhausted (-> GarminIncrementalSyncException,
                       "INCREMENTAL_WINDOW_INCOMPLETE", checkpoint NOT advanced)
```
Every item in every fetched page is ingested through the same
`GarminActivityIngestionService` as `syncRecent` (idempotent insert/update), so an
activity inside the overlap window is deliberately re-ingested every run — that is how
a Garmin-side edit (distance/HR/title/timezone correction) after upload gets picked up.
The checkpoint only advances when the whole run succeeded: no connector-level failure
(propagates immediately, before the checkpoint is touched — a `GarminConnectorException`
mid-pagination leaves already-ingested pages in place but the checkpoint untouched, so
the next run re-covers the same ground via the overlap), no malformed item
(`failed == 0`), and — for an incremental (non-bootstrap) run — the cutoff was actually
reached within `max-pages`. **Cursor is never `activityId`** (Garmin does not guarantee
id ordering) **and never an exclusive timestamp** (two activities can share a
`startTimeGMT`); the high-water mark plus overlap plus idempotent ingestion is what
makes both of those safe.

Invariants: **Python knows no DB. Spring knows no Garmin password/token. Mapper knows
no network.** Connector-level failures (401/403/429/502/unreachable) abort a sync;
per-activity failures (unsupported type → skipped, malformed → failed) do not.

### Pipeline (must stay this shape)

```text
Garmin payload
  → mapper.extractActivityId               (no id → fail, nothing stored: no key to store under; never invent an id)
  → ActivityRawService.saveOrUpdate        → activity_raw          [own transaction, committed]
  → GarminActivityMapper.map               → NormalizedActivity    [pure; failure leaves raw]
  → ActivityService.upsertExternalActivity → activity              [own transaction]
  → ActivityRawService.linkToActivity      → activity_raw.activity_id [own transaction]
```

`GarminActivityIngestionService` is intentionally not `@Transactional`; transaction
boundaries live in the called services (different beans, so proxies apply). Keep it that way.

### Policies

- **Idempotent**: same `GARMIN + externalId` → 1 `activity` row, 1 `activity_raw` row;
  re-ingestion updates normalised fields, `payload`, `fetched_at`, keeps both ids.
- **Reprocess**: `reprocess(garminActivityId)` re-maps from the stored JSONB only and
  never contacts Garmin. After a mapper change, reprocess historical raws; do not
  re-download.
- **Unsupported activity type** → `UNSUPPORTED_GARMIN_ACTIVITY_TYPE` exception, raw kept.
  Never default to `RUN`. Skip/aggregate policies belong to a future batch/scheduler layer.
- **Contract (Phase 3B-1 CONFIRMED_SOURCE → Phase 3B-3 CONFIRMED_LIVE for the fields
  below)**: fixtures and the mapper follow one item of Garmin Connect's activity list
  (`/activitylist-service/activities/search/activities`, python-garminconnect
  `get_activities()`): `activityId` int, `activityType{typeId,typeKey,parentTypeId}`
  (live-confirmed key: `treadmill_running` → `ActivityType.TREADMILL_RUN`),
  `startTimeGMT` `"yyyy-MM-dd HH:mm:ss"` UTC **without** zone designator (primary,
  live-confirmed to map to the correct UTC `Instant`), `startTimeLocal` (no zone,
  never sufficient alone), `duration` **seconds** (float, live-confirmed),
  `distance` metres (live-confirmed), `averageHR`/`maxHR` bpm (live-confirmed).
  Real type keys: `running`, `treadmill_running`, `indoor_cycling`, `virtual_ride`,
  `indoor_running`, `trail_running`, `track_running`. The response may be a bare
  list or `{"activityList": [...]}`. Full evidence and confidence per field:
  `docs/work-orders/2026-09-29-garmin-live-contract-investigation.md` (source
  study) and `docs/work-orders/2026-09-29-garmin-live-e2e-validation.md` (live
  confirmation). `activityName` is present as a live Korean-text string but is
  **not** part of `NormalizedActivity` — see the known issue below before ever
  promoting it to a normalized field.
- **Access strategy (ADR in the same work order)**: Option B — a separate Python
  connector process using `python-garminconnect` (pinned, ≥ 0.3.5 for
  CVE-2026-54447; studied 0.3.16) owns Garmin auth, tokens (`~/.garminconnect`, outside
  the repo) and read transport; Spring never sees Garmin credentials. `garth` is
  deprecated (2026-03) — do not add it. The official Developer Program is
  business-only and is the long-term migration path, not an option now. Never write
  SSO/Cloudflare/TLS-fingerprint bypass code in this repo; on 401/403/429 stop, do not
  loop.
- **Known issue — `GARMIN_ACTIVITY_NAME_ENCODING`**: a real Garmin `activityName`
  containing Korean text was reported as mojibake when displayed in a Windows
  PowerShell 5.1 terminal. Phase 3B-3 traced this end-to-end against a live
  activity: the `python-garminconnect` return value, the connector's FastAPI JSON
  response bytes, and the value stored in `activity_raw.payload` (JSONB) in
  PostgreSQL were all verified to be well-formed UTF-8 Korean text (valid Hangul
  syllable code points, no Latin-1-in-UTF-8 mojibake signature). The corruption is
  therefore isolated to the PowerShell 5.1 console/font display layer, not to the
  connector, Spring, or the database. **Do not** add any latin1/cp1252 re-decode
  workaround to the connector or to `GarminActivityMapper` — the data is already
  correct; such a "fix" would corrupt genuinely clean strings. This stays open only
  as a display-layer curiosity; it must be re-diagnosed (not assumed fixed) before
  `activityName` is ever promoted to a normalized `Activity` field.

## Intervals.icu — current state (Phase 5C-3)

`com.runningai.integration.intervals`: `IntervalsWorkoutRenderer` + `GarminSafeCueFormatter` (text only) and the
publisher stack `IntervalsWorkoutPublisher` → `IntervalsWorkoutClient` (interface) → `HttpIntervalsWorkoutClient`
(`RestClient`, bean `intervalsRestClient`), config `IntervalsProperties` (`running-ai.intervals.*`: `base-url`,
`athlete-id` default `0`, `api-key` from `INTERVALS_API_KEY` only, connect/read timeouts).

- Contract (legacy investigation + official docs): HTTP Basic, user `API_KEY`, key as password; athlete id `0` =
  key owner; `GET /api/v1/athlete/{id}/events?oldest&newest&category=WORKOUT`, `GET/PUT .../events/{eventId}`,
  `POST .../events`; fields `category, start_date_local (…T00:00:00), type, name, description, external_id`.
- Ownership marker = `external_id` `runningai:workout:v1:<athleteId>:<yyyy-MM-dd>` (description stays exactly the
  rendered text). A legacy `[RunningAI-Control]` description marker with no `external_id` is recognised and the
  event is updated in place (takes over the new marker). Foreign events are never modified; a date that holds only
  foreign events is `UNMANAGED_WORKOUT_CONFLICT`; 2+ owned events are `DUPLICATE_OWNED_WORKOUT`.
- Never retry a write. A POST with unknown outcome (timeout, connection failure, 5xx) is resolved by looking the date
  up by marker, not by posting again. After CREATE/UPDATE the event is read back (marker, date, text); a difference is
  `READBACK_MISMATCH`.
- `INTERVALS_API_KEY` is never logged or put in an exception; tests use synthetic keys, a mock server or the stateful
  `FakeIntervalsWorkoutClient`, and any Spring test that could publish pins a blank key and an unreachable URL.
- Not implemented: scheduler / automatic daily publishing (the only caller is the manual Phase 6A trigger, off by default), deletion/cancel,
  persistence of remote ids. Garmin 265 device validation (Phase 5C-4): pace target, %LTHR bpm target and treadmill cue (cue before duration/target) all DEVICE_VERIFIED; in-run gauge/alert NOT TESTED. Live validation (Phase 5C-3.5, SERVER_VERIFIED: CREATE → NO_CHANGE → UPDATE same id → NO_CHANGE, external_id round-trips) needs a real key and a safe empty date; on this PC the JVM needs `-Djavax.net.ssl.trustStoreType=Windows-ROOT`.

## Intervals.icu read-only enrichment (Phase 6H-5)

Live contract LIVE_VERIFIED 2026-10-03 against the real account (details and §49 record:
`docs/work-orders/2026-10-03-phase-6h-5-intervals-enrichment-result.md`; architecture:
`docs/architecture/intervals-enrichment.md`). Rules that must not be broken:

- **Enrichment never touches the workout client.** `IntervalsReadClient` → `HttpIntervalsReadClient` is GET only and
  declares no write method; auth + error mapping are shared with the publisher via `IntervalsHttp` (one request per
  call, no retry, 401/403/429 stop — a `Retry-After` is recorded, never waited out automatically).
- Live endpoints: `GET /api/v1/athlete/0/activities?oldest&newest` (array), `GET /api/v1/activity/{id}` (same object
  shape as a list item, no extra keys), `?intervals=true` adds only `icu_intervals`+`icu_groups` (reference, not
  normalised), `GET /api/v1/athlete/0/wellness?oldest&newest` (array, `id` = ISO date). Two live timestamp forms:
  `start_date` ends in `Z`; `analyzed`/`updated` carry `+00:00` — parse with OffsetDateTime, not Instant.parse.
  Rate-limit headers were absent on live 200s. A request without a User-Agent got 403 at the edge during the probe.
- **Terminology is load-bearing**: CTL = Intervals calculated fitness, ATL = Intervals calculated fatigue; the wellness
  `fatigue` field is subjective, is never read by the mappers and must never be mapped to ATL; `derived_form = ctl - atl`
  is RunningAI-derived and says so (no ambiguous `fatigue` column anywhere).
- Raw-first into `intervals_raw_payload` (ACTIVITY = matched list item keyed by the Intervals id; WELLNESS_DAY keyed by
  the date), own committed transaction; `reprocess` endpoints re-map from storage with **zero** Intervals calls.
- An Intervals activity is linked to the existing Garmin-based activity via `activity_source_link` (V17), never created
  as a second Activity row. Matching: SOURCE_ID (`source=GARMIN_CONNECT`, `external_id` = Garmin id — live 5/5) →
  EXTERNAL_ID → COMPOSITE with measured tolerances (|start| <= 30 s, |duration| <= 5 s vs `elapsed_time`,
  |distance| <= 5 m, live-observed type pairs RUN↔Run / TREADMILL_RUN↔VirtualRun / INDOOR_CYCLING↔VirtualRide only; a
  candidate explicitly claiming a different Garmin id is excluded; 0 candidates → UNMATCHED is normal, 2+ → AMBIGUOUS is
  never auto-linked; an id already linked elsewhere → `INTERVALS_LINK_CONFLICT`, manual decision).
- Normalised values live only in `activity_intervals_metrics` / `intervals_fitness_daily` — never in
  `activity_analysis`, never in `garmin_recovery_daily`, never in `activity_sample` (Garmin stays source of truth).
- Manual triggers only (`POST /api/v1/intervals/enrichment/...`); the fitness window is capped at 31 days because the
  90-day backfill is a separate, not-run phase. No scheduler, no webhook. Tests replace the read client with a
  scripted fake and pin a blank key + unreachable URL; fixtures are `LIVE_SHAPE.ANONYMISED` under `fixtures/intervals/`.

## Canonical publishing path and legacy retirement (Phase 5C-5)

- `IntervalsWorkoutPublisher` is the **canonical** workout publishing path; `HttpIntervalsWorkoutClient` is the only code that
  POST/PUTs Intervals events. Any future trigger (application service, scheduler, API, ChatGPT tool) must call the publisher and
  must not issue its own Intervals write, and must never run a second writer next to it.
- Legacy PowerShell (`create-today-workout.ps1`, `intervals-structured-workout.ps1`, Command Channel) is DEPRECATED, lives only on the
  main PC (not in this repo), and is reference / manual rollback only. **No automatic fallback** to legacy, ever (especially after a
  create timeout or unknown outcome — resolve it by marker lookup). Rollback is a manual operational decision; never run both writers.
- Keep legacy marker compatibility (`[RunningAI-Control]` without `external_id` → adopt and update in place) until a separate cleanup
  phase; do not delete legacy files or remove that compatibility as part of unrelated work.
- Disabling legacy scheduled tasks is a machine operation for the owner (commands in
  `docs/work-orders/2026-10-01-phase-5c-5-legacy-publishing-retirement-result.md`); never change Scheduled Tasks, env vars or
  credentials from the repo or commit them.

## Operational publish trigger (Phase 6A)

- `WorkoutPublishApplicationService.publish(date)` is the single application entrypoint to publishing:
  `WorkoutIntensityTargetService` → `StructuredWorkoutMapper` → `IntervalsWorkoutRenderer` → `IntervalsWorkoutPublisher`. It recomputes and
  formats nothing; controllers, a future scheduler (6B) and a future ChatGPT tool call it and never the publisher/client directly.
- `running-ai.workout-publishing.enabled` (`WORKOUT_PUBLISHING_ENABLED`) defaults to **false** and must stay false while the legacy
  main-PC writer is active; disabled → 409 `WORKOUT_PUBLISHING_DISABLED`. A per-date in-memory guard rejects a concurrent same-date publish
  with 409 `WORKOUT_PUBLISH_ALREADY_RUNNING` and is always released in `finally`; no distributed lock.
- Any Spring test that could reach the publisher pins `running-ai.intervals.api-key=` blank and an unreachable base URL and mocks the
  publisher / prescription source, exactly like the 5C wiring test; `POST /api/v1/workout-publish` has no authentication (private network only).

## Workout publishing scheduler (Phase 6B)

- `WorkoutPublishingScheduler` (`@ConditionalOnProperty running-ai.workout-publishing.scheduler.enabled=true`, default false; cron default
  `0 0 5 * * *`, zone default `Asia/Seoul`) calls only `WorkoutPublishApplicationService.publish(today)`; "today" is `LocalDate.now` in the configured
  zone from the shared `Clock`, never the OS default zone. It adds no lock (6A's per-date single-flight covers overlap with manual POST), no retry and no
  missed-run catch-up, and every failure is logged (date + error code / exception class only) without escaping the scheduler thread.
- The scheduler switch is independent of the master switch and never bypasses it. Tests that enable the scheduler mock `WorkoutPublishApplicationService`
  and pin a blank Intervals key and an unreachable URL; never enable it outside tests until the main-PC legacy writer is disabled and a manual
  smoke test passed (turn master on first, scheduler last).

## MCP workout tool (Phase 6C)

- `com.runningai.integration.mcp`: `PublishWorkoutMcpTool` (adapter; depends only on `WorkoutPublishApplicationService` + `ObjectMapper`) and `McpToolConfig`
  (only with `running-ai.mcp.enabled=true`; registers the tool as a `List<McpStatelessServerFeatures.SyncToolSpecification>` bean — Spring AI collects
  List beans, a single spec bean is silently ignored). Spring AI 1.1.8 / MCP SDK 0.18.3, `spring.ai.mcp.server.protocol=STATELESS`, endpoint `/mcp`.
- `spring.ai.mcp.server.enabled` defaults to **true** in Spring AI; keep it bound to `${running-ai.mcp.enabled}` (`RUNNINGAI_MCP_ENABLED`, default false).
  Keep annotation scanning and resource/prompt/completion capabilities off; add a tool only deliberately (tool names are stable API).
- Tool input is an explicit ISO date only (no natural language, no extra arguments, never credentials); failures return `isError=true` with
  `{"success":false,"code":…}` using the 6A codes; no retry, no MCP lock, no direct Intervals access; the master switch is never bypassed.
- Do not upgrade Spring Boot or move to Spring AI 2.x for MCP; check `gradlew dependencies` keeps Boot 3.5.16 after any MCP dependency change.
- `/mcp` is unauthenticated: local/private only. Connecting ChatGPT (and its auth/tunnel) is a separate operational step after the main-PC cutover.

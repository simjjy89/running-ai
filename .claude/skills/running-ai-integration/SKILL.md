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

**Not implemented — do not assume it exists:** scheduler / `@Scheduled`, sync HTTP API
(`POST /api/v1/garmin/sync`), connector process supervision, FIT/TCX/details/splits
collection, Intervals.icu, historical backfill beyond `max-pages`. Phase 3B-3
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

## Intervals.icu — current state

No Spring server implementation exists yet. When it comes: `IntervalsClient` (network)
→ application service (ingestion / workout push), with the same raw-first, external-id
and secrets rules. Investigate the existing PowerShell workflow before designing it.

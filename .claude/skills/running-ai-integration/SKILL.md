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

## Garmin — current state (Phase 3B-2)

Implemented in `com.runningai.integration.garmin`:
`GarminActivityMapper` (pure: `extractActivityId`, `parse`, `map`),
`GarminActivityPayload`, `GarminActivityMappingException` (+ `Reason`/code),
`GarminActivityIngestionService` (`ingest(JsonNode[, fetchedAt])`, `reprocess(id)`),
`GarminIngestionResult`; transport boundary `GarminActivitySource` (interface) →
`HttpGarminActivitySource` (`RestClient`, `GET {base-url}/activities?limit=N`, one
request, no retry, errors → `GarminConnectorException` + `Reason`), config
`GarminConnectorProperties` (`running-ai.garmin.connector.base-url`, timeouts; **no
credentials**); `GarminSyncService.syncRecent(limit)` → `GarminSyncResult(fetched,
created, updated, skipped, failed)`. Fixtures: `server/src/test/resources/fixtures/garmin/`.

Python connector `tools/garmin-connector/` (`python -m garmin_connector
login|status|serve|activities`): owns Garmin auth/MFA/token store (`~/.garminconnect`),
binds 127.0.0.1 only, returns raw items as a JSON array, error contract
`{code,message}` with `GARMIN_AUTH_REQUIRED|FORBIDDEN|RATE_LIMITED|UPSTREAM_ERROR|CONNECTOR_ERROR`.
It never touches the database and never normalises. No HTTP login endpoint.

**Not implemented — do not assume it exists:** incremental sync cursor, scheduler /
`@Scheduled`, sync HTTP API (`POST /api/v1/garmin/sync`), connector process
supervision, FIT/TCX/details/splits collection, Intervals.icu. Phase 3B-3
(`docs/work-orders/2026-09-29-garmin-live-e2e-validation.md`) ran the full
Connector → Spring → PostgreSQL path against one real Garmin activity twice (live
login, live fetch, live sync, live idempotency all passed) — the main contract
fields below are now **CONFIRMED_LIVE**.

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

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

## Garmin — current state (Phase 3A, offline)

Implemented in `com.runningai.integration.garmin`:
`GarminActivityMapper` (pure: `extractActivityId`, `parse`, `map`),
`GarminActivityPayload`, `GarminActivityMappingException` (+ `Reason`/code),
`GarminActivityIngestionService` (`ingest(JsonNode[, fetchedAt])`, `reprocess(id)`),
`GarminIngestionResult`. Fixtures: `server/src/test/resources/fixtures/garmin/`.

**Not implemented — do not assume it exists:** Garmin authentication, session/token
handling, any network client, real payload fetch, scheduler, incremental sync cursor,
FIT/TCX parsing. "Garmin ingestion works" currently means fixture ingestion only.

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
- **Synthetic contract warning**: the fixture shape (`duration` in **milliseconds**,
  `startTime` ISO-8601 with offset, `activityType.typeKey`, `distance` metres,
  `averageHR`/`maxHR`) is not confirmed Garmin API. When real payloads arrive, adjust the
  mapper (single place) and reprocess; update fixtures and this section.

## Intervals.icu — current state

No Spring server implementation exists yet. When it comes: `IntervalsClient` (network)
→ application service (ingestion / workout push), with the same raw-first, external-id
and secrets rules. Investigate the existing PowerShell workflow before designing it.

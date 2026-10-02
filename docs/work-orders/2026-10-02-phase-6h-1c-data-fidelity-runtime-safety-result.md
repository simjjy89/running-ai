# Phase 6H-1C — Data Fidelity & Runtime Safety Hardening (result)

**Outcome: `PHASE_6H_1C_DATA_FIDELITY_READY`.**

Instruction kept verbatim in
`docs/work-orders/2026-10-02-phase-6h-1c-data-fidelity-runtime-safety-instruction.md`.

No secret value, Garmin token, credential, real activity id or coordinate appears in this document or in any
committed file. Real ids stay in the git-ignored local note `.runtime/live-contract/activity-ids.local.md`.

---

## 1. Repository

| | |
|---|---|
| Repository / branch | `C:\running-ai-github`, `main` |
| Baseline | `a8c8b67` — working tree clean, `main` already level with `origin/main`, `a8c8b67` confirmed an ancestor |
| Pull | nothing to fast-forward (0 ahead / 0 behind); no reset, no clean, no rebase, no force |
| Final | `ece5de8`, plus one follow-up commit that only corrects the SHAs recorded below |
| Legacy repo `C:\running-ai` | read-only inspection only; not modified |

## 2. Runtime write switches (§3) — new permanent baseline

`.env` was backed up byte-exactly to `.runtime/backup/env.before-6h-1c.<timestamp>` (git-ignored, confirmed with
`git check-ignore`) before any change.

| Flag | Before | **Now (permanent)** |
|---|---|---|
| `WORKOUT_PUBLISHING_ENABLED` | `true` | **`false`** |
| `WORKOUT_PUBLISHING_SCHEDULER_ENABLED` | `false` | `false` |
| `RUNNING_AI_DRAFT_PUBLISHING_ENABLED` | absent (default false) | **`false`, now written explicitly** |
| `RUNNINGAI_MCP_ENABLED` | `true` | **`false`** |

Draft publishing was made explicit rather than left to the default, so the baseline is visible in the file instead of
having to be inferred. These values are **not** restored to their previous state at the end of this phase — all-false
is the architecture's normal baseline from now on.

`.env` is git-ignored and was not committed. Throughout this phase its contents were only ever inspected through a
redacting helper (booleans shown, secrets as `<SET>`); no `git diff` or raw dump of the file was taken (§19).

Verified on the running server: `POST /mcp` → **HTTP 404**, i.e. the Spring AI MCP server is not registered. The
publish endpoints were deliberately **not** called, even to observe a 409 — a disabled-switch probe is not worth the
risk of a misconfiguration turning into a real write.

## 3. Legacy writer inventory (§4) — nothing enabled, nothing changed

Fourteen `RunningAI-*` scheduled tasks exist. **No task was created, deleted, enabled or disabled in this phase.**

The four named in the instruction are all already **Disabled**:

| Task | State | Action |
|---|---|---|
| `RunningAI-TodayWorkout` | **Disabled** | `run-today-workout-hidden.vbs` |
| `RunningAI-TrainingCommand` | **Disabled** | `run-training-command-hidden.vbs` |
| `RunningAI-CommandChannel` | **Disabled** | `run-command-channel-hidden.vbs` |
| `RunningAI-RemoteWakeupScheduler` | **Disabled** | `run-command-channel-hidden.vbs` |

The still-enabled tasks were judged by their actual command line, not their name:

- Only two legacy scripts POST/PUT to intervals.icu: `create-today-workout.ps1` and `command-channel-worker.ps1`
  (plus one test script). Tracing every non-test caller, they are reachable only through
  `run-today-workout-hidden.vbs`, `run-command-channel-hidden.vbs` and `process-training-commands.ps1` — that is,
  only through the four disabled tasks above.
- `RunningAI-ChatGPT-Sync`, `RunningAI-NewRunCheck`, `RunningAI-DailyBrief`, `RunningAI-WeeklyReport` read
  Intervals (`Invoke-RestMethod -Method Get`) and write local files; none reaches a writer.
- `RunningAI-TrainingDecision` approves/rejects command files locally; `approve-training-command.ps1` contains no
  Intervals call, no writer invocation and no task trigger.
- `RunningAI-NtfyDecisionListener` on APPROVE calls `Start-ScheduledTask -TaskName 'RunningAI-TrainingCommand'`,
  which is disabled — so the chain terminates there. **Worth the owner knowing:** that approval path is now a dead
  end that fails quietly (`NTFY_TASK_TRIGGER_FAILED`), not a write risk.
- `RunningAI-CommandBridge` (node `bridge/transports/http.js`) contains no Intervals or writer reference; its last
  run returned 1 (already failing before this phase).
- `RunningAI-Startup` runs **this** repository's `scripts\windows\start-running-ai.ps1` — the canonical runtime
  launcher, correctly left enabled.

**External workout writes this phase: 0.**

## 4. Sample request policy (§5)

`GarminActivityDetailProperties.samplesMaxChartSize` is now a non-null `Int` defaulting to **20000**, bound from
`running-ai.garmin.detail.samples-max-chart-size` / `GARMIN_DETAIL_SAMPLES_MAX_CHART_SIZE`, and is **always** sent —
only on `/samples`, never on another part.

Validation is `1..100000`, which is not an invented bound: it mirrors `MAX_CHART_SIZE` in
`tools/garmin-connector/garmin_connector/client.py`, so a value the connector would refuse fails startup instead of
failing every sample request. No upper bound beyond the connector's own was introduced.

## 5. Completeness is read, not assumed (§6, §8, §9)

- Domain (`activity.detail`): `SampleCompleteness { FULL, DOWNSAMPLED, UNKNOWN }` and `SampleStreamFidelity`
  (`completeness`, `requestedMaxChartSize`, `sourceMetricsCount`, `sourceTotalMetricsCount`), attached to
  `DetailPartRecord` as a nullable field — meaningful only for the sample stream. No Garmin JSON key appears in the
  domain.
- Integration: a separate `GarminSampleMetadataMapper` reads `metricsCount`, `totalMetricsCount` and the number of
  `activityDetailMetrics` entries. `GarminSampleMapper` was not extended. A count that is not a non-negative integer
  is reported as absent rather than repaired.
- Classification (`SampleStreamFidelity.of`), deterministic:

```text
FULL          stored == totalMetricsCount, and metricsCount agrees with the entries present
DOWNSAMPLED   stored <  totalMetricsCount, and metricsCount agrees with the entries present
UNKNOWN       no usable totalMetricsCount
              OR metricsCount disagrees with the entries present
              OR stored > totalMetricsCount
```

The rule is one-directional on purpose: FULL has to be earned; every ambiguity falls to UNKNOWN. The reported counts
are stored exactly as the source gave them even when they are the reason for UNKNOWN.

## 6. Migration V14 (§7, §23)

`V14__add_sample_collection_fidelity.sql` — four `ALTER TABLE ... ADD COLUMN` statements on
`activity_detail_collection` plus one CHECK:

```text
requested_max_chart_size    INTEGER       NULL
source_metrics_count        INTEGER       NULL
source_total_metrics_count  INTEGER       NULL
sample_completeness         VARCHAR(16)   NULL, CHECK (NULL OR FULL/DOWNSAMPLED/UNKNOWN)
```

`item_count` keeps its meaning as the stored row count; no duplicate `stored_item_count` column was added. V1–V13
were not touched. The "only meaningful for `ACTIVITY_DETAILS_STREAM`" rule is expressed as nullability, not as a
conditional CHECK over `payload_type` — such a constraint would have to be restated for every future part type, and
the ingestion service is the single writer.

Verified:

| Database | Result |
|---|---|
| H2 (tests) | V1–V14 applied, all SUCCESS |
| PostgreSQL 17, throwaway `running_ai_6h1c` | V1–V14 applied, `bool_and(success) = t`; four new columns present and `is_nullable = YES`; dropped afterwards |
| PostgreSQL 17, **live** `running_ai` | V14 applied on startup, `success = t`; **every pre-existing row preserved** — activity 5, activity_raw 7, activity_raw_payload 20, activity_detail 4, laps 65, zones 35, samples 5354, collection 24 (identical before and after); all six `jsonb` columns unchanged; the four existing sample rows kept `sample_completeness = NULL` (no back-fill) |

No repair, drop or truncate was run against the live database.

## 7. Reprocess semantics (§11)

`reprocess` still makes zero Garmin calls. Completeness is recomputed from the stored raw payload, because both
counts live inside it.

`requestedMaxChartSize` does not live in the payload. Rather than nulling it (losing a fact) or inferring it
(inventing one), it is **carried forward from what the previous collection recorded** — our own record, not a guess —
and stays `NULL` for payloads stored before it was ever recorded. This is a deliberate reading of the instruction's
"do not estimate": preserving a recorded value is not estimation. Both behaviours are covered by tests.

## 8. API response (§14)

Chosen: **additive fields on the existing collect/reprocess response**, so the information is available without a
second call. `DetailPartResult` gained `sampleCompleteness`, `requestedMaxChartSize`, `sourceMetricsCount` and
`sourceTotalMetricsCount`, all null for every part but the sample stream. No existing field changed name, type or
meaning. The same values are also queryable from `activity_detail_collection`.

## 9. Live validation on the long outdoor run (§12, §16)

One activity only — the long outdoor run — per the minimum-live-call rule. The other three were left as they are.

| | Before (Phase 6H-1B) | **After** |
|---|---|---|
| requested maxChart | not recorded (library default 2000) | **20000** |
| `source_metrics_count` | — | **2784** |
| `source_total_metrics_count` | — | **2784** |
| stored sample rows | 1399 | **2784** |
| completeness | (DOWNSAMPLED, measured out-of-band) | **FULL** |

First collection: `COMPLETE`, 8.0 s, other parts unchanged (splits 10, HR zones 5, power zones 5, detail
`RAW_STORED`).

**Second collection** (re-fetched from Garmin): `COMPLETE`, same 2784 / 20000 / 2784 / `FULL`. Database identical —
samples 6739, collection rows 24, raw payloads 20, and the same md5 over every stored sample; duplicate checks on
`(activity_id, sample_index)`, `(activity_id, payload_type)` and
`(external_source, external_activity_id, payload_type)` all returned **0**.

**Reprocess**: `COMPLETE`, same 2784 / 20000 / 2784 / `FULL`, counts and checksum unchanged.

**Garmin calls, from the connector's own log:** exactly 2 per part × 5 parts = **10** (the two collections). The log
records nothing during the reprocess window — direct evidence of 0 Garmin calls for reprocess, independent of the
code path.

## 10. No hidden retries (§13)

`Garmin(retry_attempts=0)` is untouched. No retry loop was added. A `DOWNSAMPLED` answer is recorded and left alone;
there is no automatic re-request at another size, and nothing lowers `maxChartSize` to retry a 429. 401/403/429 still
stop a run immediately. **401/403/429 occurrences this phase: 0.**

## 11. Tests (§15, §22, §24)

| Suite | Baseline | Result |
|---|---|---|
| Spring `gradlew clean test` (JDK 21, H2) | 921 | **947 passed, 0 failed, 0 skipped** (97 suites, +26) |
| Python connector `pytest` | 134 | **134 passed** (connector unchanged) |
| PostgreSQL 17 subset | 397 | **423 passed, 0 failed** |

New tests cover every case the instruction lists:

- **configuration** — `GarminActivityDetailPropertiesTest`: nothing configured binds 20000; an explicit property
  binds 3500. Plus unit coverage that 0, -1 and 100001 are rejected and 1 / 100000 accepted.
- **HTTP source** — `?maxChart=20000` is present on `samples` and on no other part (the parameterised per-part test
  now asserts the bare path for the other four).
- **FULL / DOWNSAMPLED / UNKNOWN / inconsistency** — `SampleStreamFidelityTest` (classification, including
  `stored > total`) and end-to-end through the real stores in `GarminActivityDetailIngestionTest`, using the live
  numbers 2784 and 1399.
- **metadata reading** — `GarminSampleMetadataMapperTest`: string / fractional / negative / null counts all read as
  absent.
- **reprocess** — zero connector calls, same row count, completeness recomputed, recorded request size carried
  forward; and a separate test where the recorded size was cleared, proving none is invented.
- **idempotency** — two collections produce identical counts and identical fidelity, no duplicates.
- **failure** — a failed sample fetch clears the previous fidelity instead of leaving a stale FULL behind.
- **schema** — V14 present, the four columns nullable, and the database itself rejecting an unknown
  `sample_completeness`.

`PublishingModeGuardTest` (Phase 6G.1) was not duplicated; it passes unchanged in the full suite, so the guard still
fails startup only when both publishing switches are on.

> The PostgreSQL subset again needed `maximum-pool-size: 2` **for the test JVM only** (many cached Spring contexts
> against one PostgreSQL otherwise exhaust `max_connections`). Production datasource settings were not touched. One
> first-run failure was my own new test binding an `Instant` through `JdbcTemplate`, which PostgreSQL rejects where
> H2 accepts it; fixed by binding a `java.sql.Timestamp`.

## 12. JDK 21 on the Main PC (§20)

Detected only; nothing was downloaded or installed. The single JDK 21 present is
`C:\Users\simjy\.jdks\openjdk-21.0.2` (with `javac`).

| | |
|---|---|
| user `JAVA_HOME` | `C:\Users\simjy\.jdks\openjdk-21.0.2` — **already correct** when this phase started |
| machine `JAVA_HOME` | `C:\Program Files\Java\jdk1.8.0_301` (stale; the user value wins for this user) |
| user `PATH` | `%JAVA_HOME%\bin` **appended** (REG_EXPAND_SZ, so it follows `JAVA_HOME`) |
| `start-running-ai.ps1` | **succeeds with no session override** — see §13 |

**Open item, left to the owner.** `java` / `javac` on `PATH` still resolve to **17.0.10**, because the machine `PATH`
is searched before the user `PATH` and begins with Oracle's `javapath` shim:

```text
[3]  C:\Program Files\Common Files\Oracle\Java\javapath        <- wins, 17.0.10
[4]  C:\Program Files (x86)\Common Files\Oracle\Java\javapath
[17] C:\Program Files\Java\jdk1.8.0_301\bin
```

Making a bare `java -version` report 21 therefore requires editing the **machine** `PATH` (removing or reordering
those Oracle shims), and the only JDK 21 on this box lives under a user profile, so it cannot simply be promoted
machine-wide. That change would alter the default Java for every application and user on the machine, so it was not
made unilaterally. It is also **not needed for RunningAI**: Gradle and `start-running-ai.ps1` both resolve Java
through `JAVA_HOME`, which is correct. Recommended, if the owner wants it: remove the two `javapath` entries (and
the stale machine `JAVA_HOME`) after checking nothing else on the machine depends on Java 8/17.

## 13. Runtime restart (§21)

Validated through the **`RunningAI-Startup` scheduled task**, which launches
`scripts\windows\start-running-ai.ps1` in a brand-new process with the real composed environment — no session
override of any kind:

```text
task lastResult = 0
Docker           RUNNING
PostgreSQL       HEALTHY
GarminConnector  UP 127.0.0.1:8765 (managed)
Spring           UP port 8080 (managed)
GET /actuator/health -> {"status":"UP"}
POST /mcp            -> 404 (MCP off)
```

All four publishing switches remain `false`. No publish switch was turned on at any point, for any reason.

## 14. Intervals API key (§18)

`INTERVALS_API_KEY configured = true`. Comparing a truncated SHA-256 fingerprint of the value in the Phase 6H-1B
backup with the current one (values never read out, never printed, never committed) shows it is **unchanged**.

**`INTERVALS_API_KEY_ROTATION = USER_ACTION_REQUIRED`** — rotation is the owner's action in Intervals.icu; no key was
generated, displayed or written by automation here. Per the instruction this does not fail the phase. No Intervals
API write was performed this phase.

## 15. Scope held (§17, §25)

```text
Historical backfill   = NOT_RUN
Intervals enrichment  = NOT_RUN
Analysis Engine       = NOT_RUN   (no HR drift, pace fade, cadence drift, repeatability,
                                   HR recovery, decoupling, threshold exposure, quality class)
TrainingContext V2    = NOT_RUN
External workout writes = 0
Garmin live API calls = 10
401 / 403 / 429       = 0
```

Only one activity was fetched from Garmin, twice.

## 16. Commits and final state

| | |
|---|---|
| `69f4611` | `feat: preserve Garmin sample fidelity metadata` — V14, domain model, metadata mapper, properties + validation, ingestion wiring, API fields, tests |
| `ece5de8` | `docs: define full-resolution activity sample policy` — architecture docs, CLAUDE.md, integration skill, this work order |

The instruction's suggested third commit, `ops: harden main PC runtime defaults`, has no git content by design: the
`.env` baseline and the JDK user environment are machine operations and are deliberately not committed. They are
recorded in §2 and §12 instead.

A third commit (`docs: record the Phase 6H-1C commit SHAs in the result document`) carries nothing but the
corrected SHAs in this table; it is referenced by message rather than by hash, because a document cannot
contain the hash of the commit that creates it.

Push: fast-forward to `origin/main`, no force. Working tree clean.

## 17. Limitations

- Only the long outdoor run was re-collected at full resolution. The other three activities still carry their
  Phase 6H-1B streams with `sample_completeness = NULL` — honest (they predate the metadata) but not yet classified.
  Re-collecting them is a one-command operation whenever wanted.
- Completeness is verified against one device and one account. A payload that omits `totalMetricsCount` has never
  been seen live; that path is covered only by tests.
- The 20000 default is larger than every activity observed so far (max native count 2784); behaviour for an activity
  with more than 20000 native points is untested in practice, though it would simply classify as DOWNSAMPLED.
- `java` on `PATH` still resolves to 17 (see §12).
- The Intervals API key has not been rotated.

## 18. Suggested next steps (not started)

1. Re-collect the remaining three activities so every stored stream carries a completeness classification.
2. Decide whether to clean up the machine `PATH` / stale machine `JAVA_HOME` (§12).
3. Rotate `INTERVALS_API_KEY` in Intervals.icu and update the Main PC `.env` only.
4. Historical backfill policy — still gated, now that full-resolution collection is operational.
5. `Phase 6H-4 — Running Analysis Engine`.

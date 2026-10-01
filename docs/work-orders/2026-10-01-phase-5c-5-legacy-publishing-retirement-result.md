# Phase 5C-5 Legacy Publishing Path Retirement — result

Baseline `fc99166`. **Docs-only change: no Java, script, schema or config change.** Nothing was deleted, no Scheduled Task was touched.

## Key finding that shapes this phase
The legacy PowerShell / Node.js automation (`create-today-workout.ps1`, `intervals-structured-workout.ps1`,
`training-workout-dispatcher.ps1`, `command-channel-worker.ps1`, their scheduled tasks) lives **only on the owner's main PC and
is not in this repository** (CLAUDE.md; Phase 5C-0 investigation). Therefore:
- this repo cannot edit, disable or add a runtime guard to those scripts, and nothing here invokes them;
- "disable the legacy write path" is split into **repo state** (done: canonical declaration, rules, duplicate-risk analysis) and
  **machine operation** (a runbook for the user, below; not executed, not committed).
Claiming that the legacy entrypoint is "blocked" by this commit would be false; it is blocked only after the user runs the machine steps.

## Write-path inventory (static scan of this repository, `fc99166`)
Scan terms: `/api/v1/athlete/`, `/events`, `INTERVALS_API_KEY`, `create-today-workout`, `intervals-structured-workout`,
`Invoke-RestMethod`, `Invoke-WebRequest`, `RestClient`, `@Scheduled`, `Register-ScheduledTask`, `IntervalsWorkoutPublisher`.

```text
Entry point:   HttpIntervalsWorkoutClient.create / update  (POST / PUT .../events)
Triggered by:  IntervalsWorkoutPublisher only
Calls:         Intervals.icu REST API (the only Intervals HTTP code in production)
Can publish:   YES (only if INTERVALS_API_KEY is set)
Still active:  CANONICAL — but currently has NO production caller (no controller, service or scheduler invokes the publisher;
               only tests and the Phase 5C-3.5 / 5C-4 temporary live runners did, and those are removed)
Action:        keep; declared canonical

Entry point:   IntervalsWorkoutPublisher.publish(date, RenderedIntervalsWorkout)
Triggered by:  nothing in production yet (future operational trigger, a later phase)
Can publish:   YES (via the client above)
Action:        canonical; any future trigger must call this and nothing else

Entry point:   GarminSyncScheduler (@Scheduled, default disabled), POST /api/v1/garmin/sync
Calls:         Garmin connector → activity ingestion only; never Intervals
Can publish:   NO

Entry point:   scripts/windows/* (RunningAI-Startup, RunningAI-Watchdog tasks)
Calls:         start / stop / status / watchdog of the Spring server; Invoke-WebRequest only for health checks
Can publish:   NO

Entry point:   Any other Intervals reference in the repo (application.yml, .env.example, README, docs)
Can publish:   NO (config / documentation)
```
Result: inside this repository there is exactly one Intervals write path and it is the Spring publisher. No direct Intervals
POST/PUT exists outside `IntervalsWorkoutClient`.

### Legacy paths (outside this repo; state per the Phase 5C-0 investigation and the user's description, not re-inspected here)
```text
create-today-workout.ps1        legacy publisher (POST/PUT/GET/DELETE events, [RunningAI-Control] marker idempotency)
                                 Triggered by: manual PowerShell, Command Channel worker, legacy automatic planner path
                                 Can publish: YES → status ACTIVE on the main PC until the user disables it → target DISABLED / RETIRED (file kept)
intervals-structured-workout.ps1 legacy renderer
                                 Can publish: NO (renders text only) → DEPRECATED REFERENCE (kept as behaviour evidence)
training-workout-dispatcher.ps1 / cross-training-structured-workout.ps1
                                 renderer dispatch / cycling renderer → REFERENCE_ONLY (cycling renderer stays legacy-only; no Spring equivalent yet)
command-channel-worker.ps1 + Google Drive command queue
                                 Triggered by: Windows Scheduled Task(s) on the main PC (e.g. a command-channel / remote-wakeup task)
                                 Can publish: YES via create-today-workout.ps1 → DEPRECATED (future replacement: ChatGPT → Spring API, not built)
legacy Windows Scheduled Tasks that run the above
                                 → machine-specific; the user disables them (runbook)
```
This PC (where the 5C work ran) has no RunningAI workout/command tasks (read-only `Get-ScheduledTask` check); the legacy tasks, if any,
are on the main PC and were not inspected.

## Canonical path declaration
`IntervalsWorkoutPublisher` is the canonical workout publishing path.
```text
Workout Recommendation → TargetedWorkoutPrescription → StructuredWorkoutMapper → StructuredWorkout
  → IntervalsWorkoutRenderer (+ GarminSafeCueFormatter) → IntervalsWorkoutPublisher → HttpIntervalsWorkoutClient
  → Intervals.icu → Garmin → Forerunner 265
```
Ownership: workout decision / structuring / rendering / Intervals publishing = Spring; Garmin delivery = Intervals ↔ Garmin integration;
PowerShell owns no workout business logic. Entry point today: none in production (publisher is a library component); the
operational trigger is a later phase. HTTP client: `HttpIntervalsWorkoutClient`. Renderer: `IntervalsWorkoutRenderer`.

## Duplicate-write analysis
- Before 5C-5 (design level): Spring and legacy used different markers (Spring: `external_id`; legacy: `[RunningAI-Control] command_id=` in the description).
- Spring after legacy on the same date: the Spring lookup recognises a legacy-marked event with no `external_id` as RunningAI-owned
  and **updates it in place** (takes over the marker): 1 event, no duplicate. (Unit-tested; not exercised live.)
- Legacy after Spring on the same date: per the recorded legacy logic (5C-0, `create-today-workout.ps1` marker rules) an event without
  its own description marker is *unmanaged* and any other/unmanaged event on the date gives `CONFLICT` with no write; a Spring-owned
  event carries no description marker, so the legacy script would refuse, not add a second event. (Source-read evidence only; the
  legacy script was not run in this phase.)
- Residual risk: a true race (both writers within the same moment, each seeing an empty day), and any legacy behaviour differing from
  what the investigation recorded. Both disappear when the legacy write entrypoints are disabled on the main PC.
- After this phase: in-repo there is a single writer; the remaining duplicate exposure is exactly "the legacy writer is still enabled
  on the main PC". It is removed by the machine runbook, which cannot be done from the repository.

## Legacy marker compatibility
- Retained: yes (`IntervalsWorkoutPublisher.LEGACY_MARKER` / `isLegacyOwned`, unchanged).
- Reason: events already created by the legacy publisher must be adopted, not duplicated, and a manual rollback to legacy must find
  Spring-created events as non-duplicating.
- Removal condition (candidate only, not done): legacy publisher disabled for a sustained period; no `[RunningAI-Control]` events
  remain in the calendar window the publisher can touch; no rollback need; Spring publishing proven stable in operation.

## Rollback
- Automatic fallback: **NO.** Spring failure — especially a create timeout / unknown outcome — must never trigger the legacy script.
  (The publisher already resolves unknown outcomes by marker lookup, never by re-POST or by another writer.)
- Manual rollback: allowed only as an explicit operational decision.
- Procedure: (1) stop the Spring-side trigger (none exists yet; later phases must provide a switch-off); (2) look at the affected
  date's events in Intervals and leave exactly one; (3) re-enable the legacy scheduled task / command channel on the main PC;
  (4) record it; (5) never run both. Re-enabling legacy over a Spring-owned event is expected to end in `CONFLICT` (see above), so
  remove or reconcile that single event by hand first.

## Machine operations (user, main PC — NOT executed, NOT committed)
Read-only inventory first (PowerShell, no changes):
```powershell
Get-ScheduledTask | Where-Object { $_.TaskName -match 'RunningAI|Command|Wakeup|Workout' } |
  Select-Object TaskName, TaskPath, State
(Get-ScheduledTask -TaskName '<name>').Actions | Select-Object Execute, Arguments   # which ones reach create-today-workout / command-channel-worker
```
For each task whose action reaches `create-today-workout.ps1` / `command-channel-worker.ps1` / the legacy planner:
```powershell
Disable-ScheduledTask -TaskName '<name>'     # reversible; do not Unregister yet
```
Do **not** disable `RunningAI-Startup` / `RunningAI-Watchdog` (they run the Spring server). Also stop any manual habit / shortcut that
runs `create-today-workout.ps1`. Other local actions: none (no credentials or env change is required; keep `INTERVALS_API_KEY` in the
environment only). Which tasks actually exist on the main PC is unknown from this repo.

## Not done (by instruction / by evidence)
- No legacy file deleted or edited (they are not in the repo). No feature flag (only one writer in the repo; a flag would be an unused abstraction).
- No runtime guard code: nothing in the repo can call legacy; a guard inside the legacy scripts is a main-PC edit outside this repo.
- No `@Scheduled`, ChatGPT/connector/endpoint, DB migration, Garmin or training-logic change.
- No Java regression rerun: no production or test Java changed (last full run 396 / 396 PASS).

## Phase 5C final status
```text
StructuredWorkout            DONE (5C-1)
Renderer                     UNIT_VERIFIED, DEVICE_VERIFIED output (5C-2, 5C-4)
Publisher                    SERVER_VERIFIED (5C-3.5)
Intervals live               SERVER_VERIFIED
PACE Garmin                  DEVICE_VERIFIED   (in-run gauge/alert NOT TESTED)
%LTHR Garmin                 DEVICE_VERIFIED   (in-run gauge/alert NOT TESTED)
Treadmill cue Garmin         DEVICE_VERIFIED
Canonical Spring publisher   DECLARED (this phase)
Legacy active publisher      STILL ENABLED ON THE MAIN PC until the user runs the machine steps above (repo cannot disable it)
```
Phase 5C is complete in the repository once the main-PC step is done and recorded; until then "legacy active write path blocked"
and "duplicate publishing risk removed" are **not yet true in operation**, only true inside this repo.

## Next recommended phase (not started)
Operational workout trigger (application service that calls the publisher) → scheduler → ChatGPT integration; later a separate cleanup
phase for legacy file deletion and marker-compat removal.

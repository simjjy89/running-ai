# Phase 5C-5 Legacy Publishing Path Retirement / Canonical Spring Publishing Path — work order

(Condensed faithful transcription of the user's instruction; rules and acceptance criteria are kept, repeated examples shortened.)

Baseline: `fc99166` (Phase 5C-4 complete). Verified: Intervals renderer UNIT_VERIFIED; publisher + server readback
SERVER_VERIFIED; PACE / %LTHR / treadmill cue / cue ordering DEVICE_VERIFIED on a Forerunner 265.

## Goal
Make the Spring `IntervalsWorkoutPublisher` the **canonical** workout publishing path and make sure the legacy publishing
paths (`create-today-workout.ps1`, `intervals-structured-workout.ps1`, Command Channel, legacy scheduler) cannot create duplicate
writes. Not a deletion phase: canonical-path decision + legacy entrypoint blocking + rollback possibility + migration state in writing.

## Rules
- Follow `CLAUDE.md`, the project skills and the Phase 5C-0 … 5C-4 documents; do not re-investigate the whole repo.
- Save this work order and a `…-result.md`. Start with `git status`, `git switch main`, `git pull --ff-only origin main`, clean tree, `fc99166` included.
- Investigate **every** entrypoint that can write an Intervals workout (create-today-workout.ps1, scheduled-task scripts, Command
  Channel, agent commands, manual PowerShell, Node entrypoints, Spring service/API, future scheduler hooks). Inventory format:
  `Entry point / Triggered by / Calls / Can publish Intervals workout / Still active / Retirement action`.
- Declare the canonical write path: `RunningAI Spring → IntervalsWorkoutPublisher`; every other path is classified
  `DISABLED | DEPRECATED | READ-ONLY | ROLLBACK-ONLY`. Ownership: decision/structuring/rendering/Intervals publishing = Spring;
  Garmin delivery = Intervals/Garmin integration; PowerShell is not a business-logic owner.
- `intervals-structured-workout.ps1` = DEPRECATED REFERENCE (not authoritative). `create-today-workout.ps1` = active write path →
  DISABLED / RETIRED, but the file is **not deleted**. Never allow Spring publisher + legacy publisher to both create a workout.
- Scheduler: inspect repo definitions and docs for any task calling the legacy publisher; actually enabling/disabling an OS
  Scheduled Task only on explicit user request; Windows Scheduled Task / env var / service / credential changes are
  machine-specific, never committed — give the user commands only. Keep repo change vs machine operation separate.
- Command Channel workout publishing → DEPRECATED (future: ChatGPT → Spring API).
- Keep the Phase 5C-3 legacy-marker compatibility (`[RunningAI-Control]` description without `external_id` is adopted and updated
  in place); do not remove it now. Document its removal conditions.
- Rollback: **no automatic fallback** (never run legacy on Spring failure, especially on create timeout / unknown outcome);
  rollback is a manual operational decision only. Document the procedure.
- Feature flag / `running-ai.workout-publishing.mode` only if two backends really must run at once; otherwise simply disabling the
  legacy entrypoint is preferred; no new abstractions. Runtime guard for legacy invocation (clear DEPRECATED/DISABLED message)
  where possible without breaking the reference functions.
- Production change allowed: legacy invocation disabling, scheduler wiring removal, deprecation comments, docs, clarification of the
  canonical Spring entrypoint. Not needed: renderer/publisher rewrite, DB migration, new HTTP client, Garmin changes, training-logic changes.
- Static scan for direct Intervals writes (`/api/v1/athlete/`, `/events`, `INTERVALS_API_KEY`, `create-today-workout`,
  `intervals-structured-workout`, `Invoke-RestMethod`, `Invoke-WebRequest`, `RestClient`): production code must reach Intervals only via
  `IntervalsWorkoutClient`; legacy reference scripts are classified separately.
- Mark legacy docs `DEPRECATED — Do not use for new workout publishing. Canonical path: Spring IntervalsWorkoutPublisher.`; state in
  docs and `CLAUDE.md`: "IntervalsWorkoutPublisher is the canonical workout publishing path."
- Tests: add regression tests only if production behaviour changes; if Spring code changed run `server\gradlew.bat clean test`
  (baseline 396 / 396); docs/scripts-only → "not rerun".
- Forbidden: deleting legacy files, DB migration, `@Scheduled`, ChatGPT plugin/connector/tool endpoint, Garmin changes, starting any phase after 5C.
- Finish with diff review, secrets check, commit (`refactor: retire legacy workout publishing path`, or for docs-heavy changes
  `docs: mark Spring workout publisher as canonical`), push.

## Definition of Done
Write-path inventory; all legacy write entrypoints identified; Spring publisher declared canonical; legacy renderer deprecated;
legacy publisher active path blocked; duplicate-publish possibility removed; marker compatibility kept; no automatic fallback;
manual rollback policy; scheduler and Command Channel relations documented; machine-specific actions separated; no legacy file
deleted; no DB migration; no Garmin change; docs updated; regression PASS if code changed; diff review; secrets check; commit; push.

## Completion report format
Canonical path / Legacy inventory (ACTIVE | DISABLED | DEPRECATED | REFERENCE_ONLY) / Duplicate risk (before, after, how prevented) /
Legacy marker compatibility (retained, reason, removal condition) / Rollback (automatic fallback, manual rollback, procedure) /
Machine operations / Tests / Database / Git / Phase 5C final status / Next recommended phase (not auto-started).

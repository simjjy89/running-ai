# Phase 5C-4 Garmin Forerunner 265 Device Validation — result

Baseline: `138ae4e` (+ docs `7870e81`). **No production code was changed.** No personal identifiers, ids, keys, LTHR value or
bpm ranges are recorded. Every DEVICE_VERIFIED below rests on the user's own observation of the real Forerunner 265 / Garmin Connect.

## Environment
- Intervals credential: configured (presence only; value never printed). Athlete id default `0`.
- Windows trust store option: `-Djavax.net.ssl.trustStoreType=Windows-ROOT` on the live-validation JVM only; certificate verification never disabled; not hard-coded anywhere.
- Garmin device: Forerunner 265 (+ Garmin Connect).
- Pre-cleanup: the Phase 5C-3.5 synthetic event was deleted manually by the user; read-only lookup confirmed 0 events afterwards.
- Method: each workout was built through the Spring pipeline (`TargetedWorkoutPrescription → StructuredWorkoutMapper →
  IntervalsWorkoutRenderer → IntervalsWorkoutPublisher`) with golden-fixture values, published on its own safe date (re-queried
  empty immediately before each publish), then verified on the device before the next one was published.

## Test A — PACE (2026-10-02)
```text
- Warm Up 5m 6:15-7:15/km Pace
- Main 3m 5:45-6:30/km Pace
- Cool Down 2m 6:15-7:15/km Pace
```
- Intervals publish: CREATED, verified=true, remote id returned
- Intervals readback: external_id present, type `Run`, name unchanged, description byte-exact, 1 event on the date
- Garmin delivery: reached Garmin / watch (delivery mode not reported)
- Garmin preview target: pace range shown on every step (Warm Up / Main / Cool Down); the earlier "목표 없음" did **not** reproduce
- Garmin active target (pace gauge / out-of-range alert while running): **NOT TESTED**
- Result: **DEVICE_VERIFIED** (target display; in-run behaviour not tested)
- Boundary: Spring PASS → Intervals PASS → Garmin PASS

## Test B — %LTHR (2026-10-03)
```text
- Warm Up 5m 65-78% LTHR hr=1s
- Main 3m 75-85% LTHR hr=1s
- Cool Down 2m 65-78% LTHR hr=1s
```
- Intervals publish: CREATED, verified=true, remote id returned
- Intervals readback: external_id present, type `Run`, name unchanged, description byte-exact, 1 event on the date
- Garmin delivery: reached Garmin Connect and the watch (delivery mode not reported)
- Garmin Connect: HR target visible
- Garmin preview target: 3 steps (5m → 3m → 2m), each a **real heart-rate bpm target**; displayed bpm = each step's %LTHR range
  converted with the user's configured LTHR (user verified the arithmetic)
- Garmin active target (HR gauge / out-of-range alert while running): **NOT TESTED**
- Result: **DEVICE_VERIFIED** (target display; in-run behaviour not tested). The previous `ASSUMED` status is retired.
- Boundary: Spring PASS → Intervals PASS → Garmin PASS

## Test C — TREADMILL cue (2026-10-04)
Cue only, no structured target:
```text
- Warm Up 8.0kph Incline0pct 5m
- Main 12.0kph Incline1pct 3m
- Cool Down 8.0kph Incline0pct 2m
```
- Intervals publish: CREATED, verified=true, remote id returned
- Intervals readback: external_id present, type `Run`, name unchanged, description byte-exact, 1 event; order `label → cue → duration`
- Garmin delivery: reached Garmin Connect and the watch (delivery mode not reported)
- Cue visible: yes — Warm Up `8.0kph Incline0pct`, Main `Main 12.0kph Incline1pct`, Cool Down `8.0kph Incline0pct`; also in Garmin Connect
  and on the watch's **active step** (tested)
- Speed visible: yes; Incline visible: yes
- Truncation: none (a line break occurs in the display; text is complete)
- Cue ordering (cue before duration/target): works on the new Spring path
- Result: **DEVICE_VERIFIED** (cue text only: this is not a Garmin structured treadmill speed/incline target and Garmin does not control the treadmill)
- Boundary: Spring PASS → Intervals PASS → Garmin PASS

## Transport
- Intervals → Garmin automatic: workouts reached Garmin, but whether delivery was automatic was **not reported**
- Garmin Connect manual sync required: not reported
- Watch sync required: not reported
(No sync automation was built or changed.)

## Boundary diagnosis
- PACE: Spring / Intervals / Garmin all PASS
- HR: Spring / Intervals / Garmin all PASS
- TREADMILL: Spring / Intervals / Garmin all PASS
No Class A–D mismatch was observed.

## Verification status (final)
```text
Intervals renderer          UNIT_VERIFIED
Intervals publisher         SERVER_VERIFIED
Intervals server readback   SERVER_VERIFIED

Pace Garmin                 DEVICE_VERIFIED   (in-run gauge / out-of-range alert: NOT TESTED)
%LTHR Garmin                DEVICE_VERIFIED   (in-run HR gauge / out-of-range alert: NOT TESTED)
Treadmill cue Garmin        DEVICE_VERIFIED
Cue ordering migration      DEVICE_VERIFIED
```
The two NOT TESTED items do not change the target-display verdicts.

## Code changes
- production code: none
- tests: none added (the temporary runner was removed); Java baseline unchanged at 396 / 396 PASS (last full run in Phase 5C-3.5, no code changed since)
- reason: no device mismatch observed

## Limitations
- One athlete / one device / one Garmin Connect account; short synthetic 10-minute workouts only (steps 5 / 3 / 2 min).
- Pace gauge and HR gauge / out-of-range alert behaviour during an actual run were not exercised.
- Mixed pace + treadmill cue in one line (the renderer's golden `Warm Up 8.3-9.6kph Incline0-0.5pct 10m 6:15-7:15/km Pace` form),
  speed *ranges* in cues, and heterogeneous target types inside one workout were not validated on the device; A, B and C each tested one form in isolation.
- Intervals → Garmin delivery mode (automatic / manual sync) not recorded.

## Cleanup
- synthetic events (2026-10-02 PACE, 2026-10-03 %LTHR, 2026-10-04 TREADMILL): **retained**; the user will delete them manually in the Intervals UI (no production delete feature added)
- temporary runners: removed (`TempLiveIntervalsRunner` deleted; scratch output outside the repo)

## Not started
Phase 5C-5 (legacy publishing path retirement / migration completion) — not started.

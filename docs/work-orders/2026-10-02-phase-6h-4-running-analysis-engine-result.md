# Phase 6H-4 — Running Analysis Engine + Java 21 Runtime Normalization (result)

**Outcome: `PHASE_6H_4_RUNNING_ANALYSIS_ENGINE_READY`.**

**Follow-up, completed 2026-10-03: `JAVA21_SYSTEM_NORMALIZED`** — see the addendum at the end. The
`JAVA21_MACHINE_PATH_USER_ACTION_REQUIRED` item recorded below in §2 has since been resolved with the
owner's explicit permission to use administrator rights; §2 is kept as the record of what was true
during the phase itself.

Instruction kept verbatim in
`docs/work-orders/2026-10-02-phase-6h-4-running-analysis-engine-instruction.md`.

No secret, Garmin token, API key, real activity id or coordinate appears in this document or in any
committed file. Real ids stay in the git-ignored `.runtime/live-contract/activity-ids.local.md`.

---

## 1. Repository

| | |
|---|---|
| Repository / branch | `C:\running-ai-github`, `main` |
| Baseline | `b3862f1` — clean, already level with `origin/main`, confirmed an ancestor |
| Pull | nothing to fast-forward; no reset, no clean, no rebase, no force |
| Final | `8031036` plus one follow-up commit recording the SHAs below |
| Legacy repo `C:\running-ai` | not touched |

## 2. Java 21 (§3–§10)

### 2.1 What was found (read-only first)

| | |
|---|---|
| JDK 21 installed | `C:\Users\simjy\.jdks\openjdk-21.0.2` — the only one on the machine; `bin\java.exe` 21.0.2, `bin\javac.exe` 21.0.2 |
| user `JAVA_HOME` | already JDK 21 |
| user `PATH` | already contains `%JAVA_HOME%\bin` (added in 6H-1C) |
| machine `JAVA_HOME` | `C:\Program Files\Java\jdk1.8.0_301` (stale relative to intent, but a real JDK) |
| machine `PATH` | `[3]` Oracle `javapath`, `[4]` Oracle `javapath` (x86), `[17]` `%JAVA_HOME%\bin` |
| Oracle shim | ordinary files (not symlinks): `java.exe` **17.0.10**, `javac.exe` **17.0.10**, `javaw.exe`, `jshell.exe` |
| previous `java -version` (new shell) | **17.0.10** via `[3]` |
| previous `javac -version` | **17.0.10** via `[3]` |

Verified against a faithfully reconstructed new-shell environment (machine registry values, then user
values, PATH concatenated machine-first), not against this session's inherited variables. The helper that
does it is `.runtime/java-env-backup-6h4/fresh-shell-probe.ps1` — it only reads the registry and launches
a child process; it changes nothing.

### 2.2 What was changed

**Nothing.** 6H-1C had already set the user-scope pieces, and the only remaining fix needs the machine
`PATH`, which requires elevation this session does not have. §6 says not to force elevation, so no
elevation was attempted and no UAC prompt was raised.

Machine `JAVA_HOME` was **deliberately left alone** too. §7 only permits normalising it to a verified
JDK 21 *root*, and the sole JDK 21 on this box lives inside a user profile — the wrong kind of target for
a machine-wide variable that services and other accounts also resolve. The precondition was not met.

Pre-change values are captured in `.runtime/java-env-backup-6h4/env-backup.json` (raw, unexpanded, with
registry value kinds; git-ignored).

### 2.3 `JAVA21_MACHINE_PATH_USER_ACTION_REQUIRED`

Windows searches machine `PATH` before user `PATH`, so bare `java` / `javac` still resolve to **17.0.10**.
The exact one-off elevated command and its rollback are in
`.runtime/java-env-backup-6h4/MACHINE-PATH-ACTION.md`; in short, prepend
`C:\Users\simjy\.jdks\openjdk-21.0.2\bin` to the machine `PATH` (nothing deleted, no unrelated entry
moved), after backing the value up. Rollback is restoring the saved string.

Worth knowing before running it: that JDK lives under a user profile, so making it the machine default
also changes `java` for services and any other account. The cleaner long-term fix is a JDK 21 under
`C:\Program Files\...` with machine `JAVA_HOME` pointed at it — §3 forbids installing one here.

### 2.4 Why this does not block the engine (§10)

| Check | Result |
|---|---|
| JDK 21 installation found | **yes** |
| `gradlew -version` in a fresh-shell environment | **Launcher JVM 21.0.2**, daemon JDK `C:\Users\simjy\.jdks\openjdk-21.0.2` |
| `start-running-ai.ps1` without any session override | **succeeds** |
| java binary the running Spring actually uses | `C:\Users\simjy\.jdks\openjdk-21.0.2\bin\java.exe` |

All three STOP conditions are absent, so the engine was built. The canonical runtime was also started
through the `RunningAI-Startup` scheduled task — a brand-new process with the real composed environment —
which returned `lastResult = 0`; **no temporary JDK path hack was added to that task or to any script.**

## 3. Migrations (§12, §14, §40)

| Migration | What it adds |
|---|---|
| `V15__add_lap_workout_structure.sql` | `activity_lap.intensity_type` (VARCHAR(32)), `.workout_index`, `.workout_step_index` — all nullable |
| `V16__create_activity_analysis.sql` | `activity_analysis` (unique per activity), `activity_analysis_interval_group`, `activity_analysis_interval` |

V1–V14 untouched. `intensity_type` has **no CHECK constraint and no database enum** on purpose: an
intensity value Garmin has not shown us yet must widen the data, never break ingestion. `extra_metrics`
still carries the raw lap keys, so the promotion loses nothing and a future mapper correction still
reprocesses from the raw shape.

Verified on H2 (tests), on a throwaway PostgreSQL 17 database (`V1–V16` all `success = t`, the three new
lap columns `is_nullable = YES`, dropped afterwards) and on the **live** database at startup. Live data
preserved: activity 5, laps 65, samples 6739, zones 35, raw payloads 20 — identical before and after. No
repair, drop or truncate was run.

## 4. Lap structure promotion and reprocess (§12, §13)

`GarminLapMapper` now reads `intensityType` / `wktIndex` / `wktStepIndex` into the first-class fields.
A non-integral index fails the mapping (`NON_INTEGRAL_METRIC`) rather than being rounded.

Reprocessing the four stored activities from their **stored SPLITS payloads**:

| | |
|---|---|
| Garmin API calls | **0** — the connector log records no part fetch during the whole reprocess |
| Outcome | all four `COMPLETE` |
| Lap counts | unchanged (10 / 24 / 24 / 7 = 65) |
| `intensity_type` populated | **65 of 65** — ACTIVE 39, RECOVERY 14, WARMUP 8, COOLDOWN 4 |
| `workout_step_index` populated | 54 of 65 (11 laps genuinely carry none — the live 6H-1B finding) |
| Raw payloads / samples | unchanged (20 / 6739) |
| Duplicates | 0 |

The reprocess also recomputed sample fidelity for the three activities that still had none, which is how
6H-1C was designed to work: `FULL` / `FULL` / `FULL` and `DOWNSAMPLED` (1366 of 2712) for the track run,
whose stream was collected before the 20000 default. `requested_max_chart_size` stays NULL for those
three — it was never recorded for them and is not invented.

## 5. The engine (§11, §32)

```text
RunningActivityAnalysisService
  ├── SessionMetricsCalculator      halves, speed-HR decoupling, cadence
  ├── ZoneExposureCalculator        time in heart-rate zone
  ├── ThresholdExposureCalculator   time at/above a share of LTHR
  ├── LapMetricsCalculator          laps as recorded
  ├── IntervalStructureExtractor    blocks, repetition groups
  └── IntervalMetricsCalculator     repeatability, recovery HR change
        → ActivityAnalysisStore
```

Calculators are pure (no I/O, no clock, no Spring state). The service reads stored rows only and can
reach neither Garmin nor Intervals.icu; nothing in the package can publish.

**`analysis_version` = `RUNNING_ANALYSIS_V1`.** A re-analysis replaces the derived rows and never writes
to `activity`, `activity_detail`, `activity_lap`, `activity_zone`, `activity_sample` or any raw payload.

### No coaching judgment (§11)

There is no rating, score, threshold, readiness or risk number, and no good/bad label anywhere in
`com.runningai.analysis` or in V16. `analysis_status` (`COMPLETE` / `PARTIAL` / `INSUFFICIENT_DATA`)
describes whether the **data** could be analysed, not how the session went.

## 6. Metrics and exact definitions (§15–§28)

Full reference: `docs/architecture/running-analysis-engine.md`. The decisions worth repeating:

- **Halves split at the elapsed-time midpoint**, never by sample index. `elapsedSeconds` first, sample
  timestamps as fallback; if neither gives a base that advances, the half metrics are **null** — there is
  deliberately no index-half fallback.
- **Valid samples**: HR present and `> 0`; speed and cadence present and `>= 0`. A zero speed is kept
  (standing still is part of the session). **No** moving-time filter, **no** speed threshold.
- `hr_change_bpm = h2 - h1`; `hr_change_percent = (h2 - h1) / h1 * 100`;
  `speed_change_percent = (s2 - s1) / s1 * 100`; a percentage is null when its baseline is null or zero.
- **`speed_hr_decoupling_percent` = `(EF1 - EF2) / EF1 * 100`**, `EF = averageSpeed / averageHR` per half.
  A **RunningAI-derived descriptive metric** — explicitly *not* Garmin's or Intervals.icu's, and it
  carries **no threshold**. Both half ratios are stored alongside it.
- **Cadence** for `RUN` / `TREADMILL_RUN` only; a ride reports null rather than a running cadence change.
- **Zone percentages use the zone total as denominator**, not the activity duration — only the former is
  something the source vouches for. A reported `0.0` zone is kept; an unreported zone is absent.
- **LTHR exposure integrates the real gaps between samples**; the final sample contributes nothing,
  because nothing says how long the last reading held. Null without an LTHR on file.
- **Every CV is a population standard deviation** (divisor `n`), documented and pinned by tests: the
  repetitions present are the whole group, and `n-1` would inflate a two-rep spread by ~41%.
- **Interval blocks** = consecutive laps sharing `intensity_type` + `workout_step_index`; the same step
  index later is a separate block. Block speed/HR/cadence/power are **weighted by lap duration**.
- **Interval group** requires ACTIVE blocks, the same step index at least twice, and a RECOVERY block
  between two occurrences. **Never inferred from a speed or HR pattern**; a fast/slow run with no recorded
  structure yields no groups.
- **`recovery_hr_drop_bpm`** is the **RunningAI interval recovery HR change** (fixed 10 s windows at each
  end of the recovery block, constant in code), **not Garmin's Recovery HR metric**.
- **Missing data is null**, never zero and never estimated.

### Sample fidelity (§29)

`input_sample_completeness` carries `activity_detail_collection.sample_completeness` into the analysis.
`FULL` computes normally; `DOWNSAMPLED` and `UNKNOWN` compute and are labelled; `null` means the stream
predates fidelity recording. Nothing is refused for fidelity. Lap and zone metrics are independent of it.

## 7. API (§33)

```text
POST /api/v1/activities/{activityId}/analysis   recompute from stored data, replace the result
GET  /api/v1/activities/{activityId}/analysis   read the last result
```

Manual only; no scheduler, no startup trigger. Unknown activity → 404 `ACTIVITY_NOT_FOUND`; never
analysed → 404 `ACTIVITY_ANALYSIS_NOT_FOUND`.

## 8. Live smoke on the four stored activities (§34–§38)

All four `COMPLETE`, `RUNNING_ANALYSIS_V1`, **zero Garmin and zero Intervals calls**. Figures rounded and
kept coarse on purpose.

| | Outdoor / long run | Track interval | Treadmill | Indoor cycling |
|---|---|---|---|---|
| samples / fidelity | 2784 `FULL` | 1366 `DOWNSAMPLED` | 1801 `FULL` | 788 `FULL` |
| HR halves | +5.2 bpm (+2.8%) | +5.0 bpm (+3.5%) | +18.9 bpm (+13.1%) | +13.1 bpm (+13.4%) |
| speed halves | −3.0% | −1.0% | +16.1% | n/a (no speed sensor) |
| speed-HR decoupling | 5.68% | 4.35% | −2.66% | **null** |
| cadence change | −1.1 spm | +0.2 spm | +0.8 spm | **null** (not a run) |
| zone total | 2783 s | 2692 s | 1792 s | 663 s |
| LTHR ≥90% | 2729 s | 0 s | 612 s | 0 s |
| laps / lap speed CV | 10 / 2.2% | 24 / 15.8% | 24 / 11.0% | 7 / null |
| interval groups | **0** | **1** (4 reps) | **1** (10 reps) | **0** |

- **Outdoor (§35)**: an auto-lapped run with no recorded structure → no interval groups, which is correct
  rather than a failure. Every session metric present.
- **Track interval (§36)**: WARMUP/ACTIVE/RECOVERY/COOLDOWN all read; one group at step 2 with 4
  repetitions; speed CV 9.8%, HR progression −5 bpm, recovery drop 9.4 bpm.
- **Treadmill (§37)**: succeeded with **no GPS and no elevation** — the lap table shows a genuine
  3 × warm-up → 10 × (30 s work / 30 s recovery) → cool-down session, read as one group of 10.
- **Indoor cycling (§32 behaviour)**: analysed, but no running-specific metric was forced on it — cadence
  and decoupling are null (no usable speed), lap speed CV null.

### Spot-checks against the database (§38)

| Check | Independent SQL | Stored analysis |
|---|---|---|
| outdoor first-half HR | 182.6 | 182.6 |
| outdoor second-half HR | 187.8 | 187.8 |
| outdoor half speeds | 3.614 / 3.506 | 3.614 / 3.506 |
| interval repetition count | 4 laps at step 2 | 4 repetitions |
| interval first / last rep speed | 2.934 / 3.785 | 2.934 / 3.785 |
| `last_vs_first_speed_change_percent` | (3.785−2.934)/2.934 = **+29.00%** | +29.00% |
| interval speed CV (by hand, population SD) | **9.81%** | 9.81% |
| HR progression | 146 − 151 = −5 bpm | −5 bpm |
| treadmill last vs first | (3.775−3.860)/3.860 = **−2.20%** | −2.20% |

Two results that looked wrong and were chased down rather than accepted:

- **+29% last-vs-first on the track session** is real, not a bug: the first work repetition averaged
  2.934 m/s against 3.785 for the last, because it began from a standstill. The engine reports the
  measurement; whether that matters is the coach's call.
- **LTHR ≥90% = 0 s on the track session** is also correct: the athlete's LTHR is 180, so the threshold is
  162 bpm, and that activity's maximum recorded heart rate is 158.

## 9. Tests (§39, §41)

| Suite | Baseline | Result |
|---|---|---|
| Spring `gradlew clean test` (JDK 21, H2) | 947 | **1011 passed, 0 failed, 0 skipped** (103 suites, +64) |
| Python connector `pytest` | 134 | **134 passed** (connector unchanged) |
| PostgreSQL 17 subset | 423 | **487 passed, 0 failed** |

New coverage: session metrics (steady / rising HR / falling speed / missing HR / missing speed / zero
speed kept / elapsed-vs-index split / timestamp fallback / no usable time base / non-advancing time base /
cadence applicability / distance), decoupling (known numeric result and null guards), zone exposure (five
zones, zero-second zone, missing zone, none at all), LTHR exposure (present, absent, uneven gaps, no time
base, missing HR), the interval extractor (block collapsing, repeated step, multi-lap work block,
no-recovery rejection, single occurrence, missing step index, unstructured run, the full live track shape,
case-insensitive and unknown intensity names), repeatability (identical reps, a hand-computed population
CV that the sample formula would fail, last-vs-first), recovery HR (normal, sparse, no samples, no HR, no
recovery block), lap metrics, persistence (insert, replace, lost structure, source data untouched) and the
API (COMPLETE run, INSUFFICIENT_DATA, laps-only PARTIAL, ride without running cadence, POST→GET, unknown
activity, never-analysed). `PublishingModeGuardTest` was not duplicated; it passes unchanged.

Two real defects were caught by these tests while writing them, both fixed: `SessionMetricsCalculator.timeline`
threw on a short sample list before its guard ran, and one LTHR expectation of mine was arithmetically
wrong (160 bpm is below the 162 threshold) — the code was right.

> The PostgreSQL subset again needed `maximum-pool-size: 2` **for the test JVM only**; production
> datasource settings were not touched.

## 10. Network, writes and scope (§42, §43, §45)

```text
Garmin live API calls   = 0
Intervals API calls     = 0
External workout writes = 0
Historical backfill     = NOT_RUN
Intervals enrichment    = NOT_RUN
TrainingContext V2      = NOT_RUN   (unchanged)
Claude prompt / workout generation / publishing / Garmin scheduler / FIT = untouched
```

No pace-only heuristic interval detection, no coaching score, no injury/risk/readiness score, no good/bad
classification.

Publishing switches at the end, unchanged from the 6H-1C baseline:

```text
WORKOUT_PUBLISHING_ENABLED=false
WORKOUT_PUBLISHING_SCHEDULER_ENABLED=false
RUNNING_AI_DRAFT_PUBLISHING_ENABLED=false
RUNNINGAI_MCP_ENABLED=false
```

Legacy writer tasks (`RunningAI-TodayWorkout`, `-TrainingCommand`, `-CommandChannel`,
`-RemoteWakeupScheduler`) remain **Disabled**; no scheduled task was created, deleted, enabled or disabled.

## 11. Intervals API key (§44)

Not used this phase. **`INTERVALS_API_KEY_ROTATION = USER_ACTION_REQUIRED`** — still the same value as in
6H-1B (compared by truncated fingerprint; the value is never read out). **Rotate it before Phase 6H-5.**

## 12. Commits

| | |
|---|---|
| `e62cd1e` `feat: promote Garmin lap workout structure` | V15, lap model/entity/mapper, mapper tests |
| `e9d04c9` `feat: add detailed running analysis engine` | V16, `analysis` package, calculators, store, service, API |
| `b47ad59` `test: cover running analysis metrics` | calculator, interval, persistence and API tests; schema test for V15/V16 |
| `8031036` `docs: define running analysis evidence model` | architecture doc, CLAUDE.md, integration skill, this work order |

The instruction's suggested `ops: normalize main PC Java 21 environment` has no git content: nothing was
changed this phase, and Windows environment variables are not committed anyway (§2, §47).

Push: fast-forward to `origin/main`, no force. Working tree clean.

## 13. Limitations

- Bare `java` / `javac` still resolve to 17 (§2.3). RunningAI itself is unambiguously on 21.
- Machine `JAVA_HOME` remains stale at JDK 8 — intentionally (§2.2).
- The engine is validated against one athlete, four activities, one device. No open-water swim, multisport,
  trail or cycling-with-power session has been analysed.
- The track interval analysis runs on a `DOWNSAMPLED` stream (1366 of 2712 native points), so its
  sample-derived averages are coarser than the outdoor run's. It is labelled as such, not corrected.
- `lap_hr_progression` and `hr_progression_bpm` are plain last-minus-first differences, not trends; two
  laps are enough to produce one.
- Recovery HR change uses the recovery block after the group's **last** repetition only; per-repetition
  recovery is not computed.
- An activity whose laps carry structure but whose samples are missing still yields interval metrics from
  laps, with no sample-derived recovery HR.

## 14. Suggested Phase 6H-5 inputs (not started)

1. **Rotate `INTERVALS_API_KEY` first** (§11).
2. Decide whether to re-collect the track interval run at `maxChart=20000` so every analysed stream is
   `FULL`.
3. TrainingContext V2: which of these measurements the coach should actually receive, and in what shape —
   the engine deliberately emits more than a prompt should carry.
4. Historical backfill policy, now that both full-resolution collection and analysis are operational.
5. The machine `PATH` decision (§2.3), if a bare `java` of 21 is wanted.

---

# Addendum (2026-10-03) — `JAVA21_SYSTEM_NORMALIZED`

The owner explicitly permitted administrator rights, so the machine-level item left open in §2.3 was
completed. Windows environment variables are not committed; the full before/after and the rollback
commands are in the git-ignored `.runtime/java-env-backup-6h4/system-normalization/ROLLBACK.md`, with raw
pre-change values in `env-backup-before.json`, `machine-path-before.txt` and `user-path-before.txt`.

## A.1 What was found

**No system-wide JDK 21 existed.** `C:\Program Files\Java` held 8, 11, 15 and 17 (plus two JREs), and the
installed-programs registry agreed. The only JDK 21 on the machine was the user-profile
`C:\Users\simjy\.jdks\openjdk-21.0.2` — Oracle OpenJDK `21.0.2+13-58`, a complete and relocatable
distribution (`bin`, `lib`, `conf`, `jmods`, `include`, 321 MB).

## A.2 What was done

| Step | Change |
|---|---|
| system JDK 21 | the existing Oracle OpenJDK 21.0.2 **copied** to `C:\Program Files\Java\jdk-21.0.2` (robocopy), matching the neighbouring `jdk-17` naming |
| machine `JAVA_HOME` | `C:\Program Files\Java\jdk1.8.0_301` → `C:\Program Files\Java\jdk-21.0.2` (no `bin`) |
| machine `PATH` | `%JAVA_HOME%\bin` **moved** from position 17 to position 3, ahead of both Oracle `javapath` entries — 23 entries before and after, nothing deleted, no unrelated entry edited, relative order preserved |
| user `JAVA_HOME` | **removed**, so the machine value governs |
| user `PATH` | the `%JAVA_HOME%\bin` entry added in 6H-1C **removed** — it was the workaround for exactly the problem now fixed properly |

**Copied rather than installed**, deliberately: it adds no new JDK vendor (the work order asked to avoid
that, and the machine's other Java installations are Oracle), it needed no download and no licence
decision taken on the owner's behalf, and it is byte-identical to the build Gradle and Spring were
already green on — so normalising the environment could not quietly change a test result.

The trade-off is recorded in `ROLLBACK.md`: a plain copy is not update-managed. Swapping it later for a
winget-installed Temurin or Microsoft OpenJDK needs nothing but repointing machine `JAVA_HOME`.

Nothing was uninstalled; Java 8/11/15/17 remain, the Oracle `javapath` files were not touched (only
out-ranked on `PATH`), and the original user-profile JDK was left in place for IntelliJ.

## A.3 Verification (new process, no override)

| Check | Before | After |
|---|---|---|
| `JAVA_HOME` | `C:\Users\simjy\.jdks\openjdk-21.0.2` (user) | **`C:\Program Files\Java\jdk-21.0.2`** (machine) |
| `where java` first | `...\Oracle\Java\javapath\java.exe` | **`C:\Program Files\Java\jdk-21.0.2\bin\java.exe`** |
| `where javac` first | `...\Oracle\Java\javapath\javac.exe` | **`C:\Program Files\Java\jdk-21.0.2\bin\javac.exe`** |
| `java -version` | 17.0.10 | **21.0.2** |
| `javac -version` | 17.0.10 | **21.0.2** |
| `gradlew -version` | Launcher 21.0.2, daemon = user-profile JDK | **Launcher 21.0.2, daemon `C:\Program Files\Java\jdk-21.0.2`** |
| canonical stop → start → status | PASS (via user `JAVA_HOME`) | **PASS**, all layers UP, actuator UP |
| running Spring process | user-profile `java.exe` | **`C:\Program Files\Java\jdk-21.0.2\bin\java.exe`** |

The start was run through the `RunningAI-Startup` scheduled task — a genuinely new process with the real
logon environment, not a reconstruction — so the Spring process path above is independent evidence that
the system environment resolves JDK 21. No temporary `$env:JAVA_HOME` was set anywhere, and no Java path
hack was added to any script or task. `RunningAI-Startup` still runs only
`powershell.exe -File scripts\windows\start-running-ai.ps1`; the `$env:JAVA_HOME = $java.Home` line inside
that script is the discovered JDK being handed to Gradle, not a hardcoded path.

## A.4 Regression after the change

| Suite | Baseline | Result |
|---|---|---|
| Spring `gradlew clean test`, fresh-shell environment, **no override** | 1011 | **1011 passed, 0 failed, 0 skipped** (103 suites) |
| Python connector | 134 | unchanged — no connector code was touched |

## A.5 Two mistakes made and corrected on the way

- The first elevated run failed at its own version check: under `$ErrorActionPreference = 'Stop'`,
  `java -version 2>&1` turns the JVM's stderr banner into a terminating `NativeCommandError` in
  PowerShell 5.1. The copy had already succeeded; `JAVA_HOME` and `PATH` were untouched by the abort.
  Fixed by relaxing the preference around that one call, then re-run.
- The fresh-shell probe helper initially reported `java` as 17 **after** the change. The environment was
  correct; the helper was wrong — it expanded `%JAVA_HOME%` through `[regex]::Escape`, which mangles a
  Windows path into `C:\Program\ Files\...`. Replaced with a `MatchEvaluator`, which neither `$` nor `\`
  can disturb. Worth noting because the same helper produced the "bare java is 17" readings in §2: those
  were nonetheless correct, since `%JAVA_HOME%\bin` sat behind the javapath shims at the time either way.

## A.6 Untouched, as required

```text
Java 8 / 11 / 15 / 17 uninstalled        = no
Oracle javapath files deleted            = no
unrelated machine PATH entries changed   = no
.env or any secret printed               = no
publishing switches changed              = no (all four still false)
Garmin / Intervals API calls             = 0
external workout writes                  = 0
repository files changed by this addendum = only this work order
```

**`JAVA21_SYSTEM_NORMALIZED`**

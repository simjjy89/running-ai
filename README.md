# RunningAI

AI-powered running training platform.

RunningAI started as a personal running-automation project running on a Windows PC
(PowerShell, Node.js, file-based state). This repository hosts the new **Spring Boot
backend foundation** that those features will migrate into step by step.

## Goals

- Garmin activity ingestion
- Training analysis
- Adaptive workout generation
- Intervals.icu integration
- Garmin workout delivery
- Activity reports
- Weekly / monthly reports

## Architecture

```text
                 ┌──────────────────────┐
  Garmin ──────► │                      │ ──────► Intervals.icu
                 │   RunningAI Server   │
  Scheduler ───► │  (Spring Boot / JPA) │ ──────► Reporting
                 │                      │
                 └──────────┬───────────┘
                            │
                        PostgreSQL
```

Inside the server, features are organised by domain package (`athlete`, `activity`,
later `workout`, `training`, `integration`, `reporting`, `scheduler`) and follow a
plain layered flow: Controller → Service → Domain → Repository. External systems will
live under `integration/*` as adapters so the domain never depends on them directly.

## Current status

### Implemented (this repository, `server/`)

- Spring Boot 3.5 / Java 21 / Gradle project with `local` and `test` profiles
- `Athlete` and `Activity` JPA entities with audit timestamps (`createdAt`, `updatedAt`)
- Default athlete provisioned at startup (name/timezone configurable, defaults to
  `default` / `Asia/Seoul`)
- Activity API: create, get by id, list (newest first)
- Request validation with a structured error body
- Global exception handling (`VALIDATION_ERROR`, `INVALID_REQUEST`,
  `ACTIVITY_NOT_FOUND`, `DUPLICATE_ACTIVITY`, `DATA_CONFLICT`, `INTERNAL_SERVER_ERROR`)
- Duplicate protection on `externalSource + externalId` (service check + DB unique constraint)
- Health endpoint `GET /api/v1/health` plus Spring Actuator `/actuator/health`
- Integration test suite (H2, no external services needed)

### Not implemented yet (planned)

Garmin login / Connect access, Intervals.icu API calls, workout generation, Garmin
structured workout delivery, weekly / monthly reports, scheduler, AI / LLM analysis,
notifications. The existing PowerShell / Node.js implementations of some of these still
live on the main RunningAI PC and are **not** part of this repository yet.

## API

| Method | Path                       | Description                          |
|--------|----------------------------|--------------------------------------|
| GET    | `/api/v1/health`           | `{"status":"UP","application":"running-ai"}` |
| POST   | `/api/v1/activities`       | Create an activity (201 + `Location`) |
| GET    | `/api/v1/activities/{id}`  | Get one activity                     |
| GET    | `/api/v1/activities`       | List activities, newest first        |

Create request example:

```json
{
  "externalSource": "GARMIN",
  "externalId": "188081596",
  "activityType": "RUN",
  "startedAt": "2026-09-29T06:30:00+09:00",
  "durationSeconds": 3600,
  "distanceMeters": 10000,
  "averageHeartRate": 155,
  "maxHeartRate": 172
}
```

`externalSource`: `GARMIN | INTERVALS_ICU | MANUAL`.
`activityType`: `RUN | TREADMILL_RUN | INDOOR_CYCLING`.
Timestamps are stored and returned in UTC (`startedAt` above is returned as
`2026-09-28T21:30:00Z`). Per-athlete timezone (`Asia/Seoul`) is kept on the athlete
for later presentation / reporting use.

Error body:

```json
{
  "code": "ACTIVITY_NOT_FOUND",
  "message": "Activity not found: 42",
  "timestamp": "2026-09-29T01:00:00.000Z"
}
```

Validation errors add an `errors` array of `{ "field", "message" }`.

## Server

- Java 21
- Spring Boot 3.5 (Web, Validation, Data JPA, Actuator)
- Gradle 8.14 (wrapper included)
- PostgreSQL for local/runtime, H2 (PostgreSQL mode) for tests

## Development

All commands run from `server/`. The Gradle wrapper needs `JAVA_HOME` pointing at a
JDK 21 (or a JDK 21 on `PATH`).

### Run tests

```powershell
cd server
.\gradlew clean test
```

Tests use the `test` profile (in-memory H2). No database or network services are needed.

### Run the server

The default profile is `local`, which expects PostgreSQL. Provide the connection via
environment variables (see `.env.example` at the repository root):

```powershell
$env:DB_URL      = "jdbc:postgresql://localhost:5432/runningai"
$env:DB_USERNAME = "runningai"
$env:DB_PASSWORD = "..."
cd server
.\gradlew bootRun
```

Then:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
```

To try the server without PostgreSQL, run it on the in-memory `test` profile:

```powershell
.\gradlew bootRun --args='--spring.profiles.active=test'
```

### Profiles

| Profile | Database                | Schema      | Use                       |
|---------|-------------------------|-------------|---------------------------|
| `local` | PostgreSQL (env vars)   | `update`    | Default; local development |
| `test`  | H2 in-memory, PG mode   | `create-drop` | Automated tests           |

Schema management currently relies on Hibernate DDL for the bootstrap phase. A
migration tool (Flyway) is planned before the schema is depended upon.

### Configuration & secrets

Secrets are never committed. `.env`, credentials, tokens and IDE/build output are
git-ignored; only `.env.example` (empty placeholders) is tracked. Supported variables:

```text
DB_URL, DB_USERNAME, DB_PASSWORD
RUNNING_AI_ATHLETE_NAME, RUNNING_AI_ATHLETE_TIMEZONE
GARMIN_USERNAME, GARMIN_PASSWORD        (reserved, unused)
INTERVALS_API_KEY                       (reserved, unused)
```

## Repository layout

```text
running-ai/
├─ server/              Spring Boot backend
├─ docs/work-orders/    Work orders and implementation records
├─ .env.example
└─ README.md
```

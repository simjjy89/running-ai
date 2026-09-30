# RunningAI — project rules for Claude Code

AI-powered personal running training platform. This repository is the new Spring Boot
backend that will absorb, phase by phase, the owner's existing PowerShell / Node.js
RunningAI automation (which lives only on the main PC and is NOT in this repo).

## Architecture (current)

- `server/` — Spring Boot 3.5.x, Java 21, Gradle wrapper, PostgreSQL 17, Flyway,
  Spring Data JPA / Hibernate 6, Jackson, JUnit 5 + Spring Test (H2 in tests).
- Packages under `com.runningai`: `common` (config, exception, health), `athlete`,
  `activity` (Activity, ActivityRaw, services, HTTP API), `integration/garmin` (ingestion core,
  connector HTTP source, incremental sync, sync API, scheduler), `training` (derived training
  load). Layering: Controller → Service → Entity → Repository.
- Implemented: Activity API, ActivityRaw JSONB storage, Garmin ingestion + incremental sync via the
  localhost Python connector (`tools/garmin-connector`; Spring never holds Garmin credentials),
  sync API and opt-in scheduler, Windows (`scripts/windows`) and Linux (`deploy/linux`) runtime
  artifacts, and duration-based training load (`/api/v1/training-load`, computed from normalised
  activities in the athlete timezone, nothing persisted).
- Planned, not started: training state (acute/chronic), Intervals.icu, workout generation, reporting.
- Skills with the detailed rules: `running-ai-dev` (workflow), `running-ai-database`
  (schema/persistence), `running-ai-integration` (Garmin / external systems).

## Before any change

Run `git status`, `git branch --show-current`, `git log --oneline -5`, then read the code
you are about to touch. Existing implementation first, guessing second. Never re-create the
legacy PowerShell / Node.js features from memory; never invent external API contracts.

## Invariants

- Keep the existing architecture and package layout; no speculative refactoring or
  future-proof abstractions; no unnecessary interfaces; no Lombok; new dependencies only
  with a clear, stated need.
- Entities are never exposed as API DTOs (DTOs are Java records). No business logic in
  controllers. External-system code stays out of domain/application logic.
- Existing contracts stay unchanged unless the task explicitly says otherwise:
  `GET /api/v1/health`, `POST /api/v1/activities` (create, duplicate → 409),
  `GET /api/v1/activities/{id}`, `GET /api/v1/activities`, `GET /actuator/health`.
- Flyway owns schema changes; Hibernate only validates (`ddl-auto: validate`).
  Applied migrations are never edited; a schema change is a new `V<N>__*.sql`.
- Time is stored as UTC `Instant` / `TIMESTAMPTZ`. Internal PKs are never external IDs;
  external identity is `external_source + external_id`.

## Secrets

Never commit Garmin credentials/tokens/session data, the Intervals.icu API key, a real
`.env`, private keys, real user GPS coordinates, or raw private user payloads (fixtures are
synthetic). Never log credentials, tokens, or full raw payloads. `.env.example` holds
placeholders only.

## Testing and completion

- After code changes run the full suite from `server/`: `.\gradlew.bat clean test`
  (Java 21 required; see the `running-ai-dev` skill). Do not report done while tests fail,
  and never delete/disable a failing regression test to get green.
- Before committing check the diff for credentials, tokens, `.env`, private activity data,
  build output and IDE files. Commit/push only when the task asks for it, after tests pass.
- Every meaningful task gets a record in `docs/work-orders/YYYY-MM-DD-<name>.md`; when the
  user supplies a detailed instruction, also keep it verbatim as `*-instruction.md`.
- Completion report: what was implemented, architecture decisions, test results,
  migration yes/no, branch/commit/push, limitations, next steps. Never describe
  unimplemented work as implemented.

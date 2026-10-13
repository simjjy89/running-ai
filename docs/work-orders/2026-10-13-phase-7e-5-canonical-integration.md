# Phase 7E-5 — Canonical main integration and a safe production rebuild path

Date: 2026-10-13
Branch: `main-3` → fast-forwarded into canonical `main`
Scope: git integration, canonical build verification, production jar release path, production regression check.
No production database change, no `origin` push, no forced history rewrite.

## 1. Why

Phases 7D and 7E were developed and operationally cut over on the `main-3` worktree while canonical
`main` stayed at `370ee8f` (pre-7D). Production was already running a jar built from `main-3`, so the
canonical checkout — the one every scheduled task, the Watchdog and the production `java -jar` line
point at — no longer matched the code production was executing. That gap is what this phase closes.

It also closes the operational defect that made the gap dangerous: production ran the **Gradle output
itself**, `server\build\libs\running-ai-server-0.0.1-SNAPSHOT.jar`. One fact caused three problems.

1. `start-running-ai.ps1 -Build` rebuilt straight into that path, so a build from an out-of-date
   worktree silently replaced a working production jar — with no backup and no check of what had just
   been produced. (Observed in 7E-4: a V20-era source overwrote a working V26 jar.)
2. The running JVM holds its jar open on Windows, so `gradlew clean` in the production worktree
   failed, and an overwrite of a live artifact is not an operation with a defined outcome.
3. "Whatever jar is newest under `build\libs`" was the production contract, so *any* build anywhere in
   that worktree changed what the next start would run.

## 2. Git integration (STEP 1)

| | commit |
|---|---|
| canonical `main` before | `370ee8f` |
| `main-3` | `2fcf403` |
| `origin/main` | `370ee8f` |
| `origin/main-3` | `24ff0b5` |
| merge-base | `370ee8f` (= canonical main, so canonical was strictly behind) |

`git merge-base --is-ancestor main main-3` → true: a genuine fast-forward of 24 commits, no divergence,
nothing to merge or rebase. `main-3..main` was empty.

Pre-merge scan of all 24 commits (190 changed paths): no `.env`, key, token, credential or certificate
file; no build output, IDE folder, jar or class file; no real GPS coordinate; the only secret-shaped
strings are deliberate test stubs (`stub-github-client-secret-do-not-reuse`, an `alg:none` test JWT,
`Bearer wrong-token-...`). `.gitignore` gained `.dev.vars`, `.wrangler/`, `server/.kotlin/` and
`node_modules/`.

One uncommitted change blocked the merge: canonical's `docker-compose.yml` carried the 7E-4 RISK-H3
loopback binding, applied live on the production host and never committed there. Its content is
byte-identical (modulo line endings) to the version in `2fcf403`, which was confirmed by diff before
anything was discarded; it was backed up, discarded, and the fast-forward restored the same content
from the commit. The binding — `127.0.0.1:${POSTGRES_PORT:-5432}:5432` — is intact afterwards, and the
running container was never restarted, so RISK-H3 stays closed.

Canonical `main` is now `2fcf403`, identical to `main-3`, working tree clean. Nothing was pushed.

Notably, the fast-forward changed **no file under `scripts/windows/`** (only additions under
`scripts/dev/` and `tools/`), so the 5-minute Watchdog could not have been swapped mid-tick by it.

## 3. Canonical build verification (STEP 2)

Run from the canonical worktree with JDK 21.0.2, with the build directory redirected to a staging
path by a throwaway init script, **specifically so the build could not touch `server\build\libs` while
the production JVM was executing the jar there**:

```
gradlew clean test bootJar   →  BUILD SUCCESSFUL in 4m 14s, 1385 tests PASSED, 0 failed
```

The production jar was re-hashed afterwards and was unchanged; production stayed UP throughout.

**Flyway V26 compatibility was measured, not assumed.** The canonical-built jar and the running
production jar differ in 18 of 923 entries — all text resources (SQL migrations, `application.yml`,
`spring.factories`), because the canonical worktree checked these out with LF while `main-3` has CRLF.
Every `.class` entry matches by name, size and CRC32. Since Flyway's checksum could in principle be
line-ending sensitive, its algorithm (CRC32 over each line's UTF-8 bytes, terminators excluded) was
computed over the canonical sources and compared against the production
`flyway_schema_history`: **all 26 applied migrations match, 0 mismatches, 0 missing**, and the LF and
CRLF forms produce identical checksums. A canonical-built jar therefore starts against the production
database without a validation failure.

## 4. The production rebuild path (STEP 3)

Production now runs a **released** jar at a stable path that is never a Gradle output:

```
build    gradlew bootJar  ->  server\build\libs\running-ai-server-*.jar   (never executed)
verify   a real boot jar? Start-Class? dependencies? which Flyway migrations?
release  atomic publish   ->  .runtime\running-ai-server.jar              (what runs)
```

New `scripts/windows/release-spring-jar.ps1` performs the three stages, separably:

- `-VerifyOnly` builds and verifies and **never touches the released jar**, so a worktree can be
  proven to build a valid artifact without changing anything production will run.
- Verification reads the jar as a zip: it must carry `org/springframework/boot/loader` entries,
  `BOOT-INF/classes`, at least one `BOOT-INF/lib/*.jar` and a manifest `Start-Class` (so a
  `*-plain.jar` or a truncated archive is rejected), and it reports the Flyway migration count and
  highest version. `-ExpectedMaxMigration 26` turns that into a hard gate — the direct guard against
  releasing an out-of-date worktree's jar.
- A release without `-AllowDirty` is **refused from a worktree with uncommitted changes**: an artifact
  whose source cannot be named by a commit cannot be reproduced or rolled back to.
- Publishing writes `<released>.new` in the same directory, re-hashes it, and renames it into place,
  so a concurrent start reads either the old jar or the new one, never a torn one. The previous
  release is copied to `.runtime\released\running-ai-server-<timestamp>-<sha8>.jar` first (5 kept).
- The publish step takes the **same named runtime lock** `start-running-ai.ps1`, `stop-running-ai.ps1`
  and Watchdog recovery already use, so it cannot interleave with a Watchdog-driven restart.
- Every release writes `.runtime\running-ai-server.jar.json`: sha256, git commit/branch/dirty,
  `Start-Class`, migration count and max migration. "Which source is production running?" — the
  question that went wrong in 7E-4 — is now answerable from the host, without guessing.
- A JVM still executing the Gradle output is detected before building and reported as an operator
  action, instead of failing halfway through a Gradle run with a file-lock error.

`start-running-ai.ps1` changes in three ways and in no others:

- `Find-SpringJar` returns the released jar when it exists, and only otherwise falls back to the
  newest jar under `server\build\libs` (so a worktree that has never released still starts);
- `-Build` delegates to `release-spring-jar.ps1` instead of calling `gradlew bootJar` itself — it
  passes `-RuntimeLockHeldByCaller`, because a Mutex belongs to the process that acquired it and a
  child cannot be handed one; without that the child would wait on its own parent;
- the jar's provenance (sha256 prefix, branch@commit, max migration) is written to the start log, so
  an unexpected production version is visible at startup rather than reverse-engineered later.

The Watchdog itself is unchanged: it already invoked `start-running-ai.ps1` **without** `-Build`, so it
never rebuilt anything; it now simply starts the released jar.

Seven regression checks were added to `scripts/windows/tests/Test-RunningAI.ps1` (97 checks, all pass):
the released path is not a build output; the start script prefers it; the start script no longer calls
`gradlew` at all; a release renames a fully written temp file rather than copying onto the live path;
the previous release is backed up before replacement; the lock is taken unless the caller holds it; and
the jar verifier accepts a boot jar and rejects a plain one (both built as throwaway zips in the test).

## 5. Production regression check (STEP 4)

See the verification table in the completion report. The restart used the **byte-identical jar already
running** (sha256 `760a2ee7…`), moved to the released path — a path change with zero code change —
with the Watchdog disabled for the window and the previous jar kept for rollback.

## 6. Known state / limitations

- Nothing was pushed. `origin/main` is 24 commits behind canonical `main`, `origin/main-3` 23 behind.
- The canonical worktree holds text files with LF where `main-3` holds CRLF, so the two worktrees do
  not produce byte-identical jars from the same commit. Harmless (Java compilation and Flyway
  checksums are both line-ending insensitive, measured above) but it means a SHA-256 comparison
  between worktree builds is not a valid equivalence test — compare entry CRC32s instead.
- `tools/mcp-gateway` now exists in the canonical worktree too, while the running gateway is still
  launched from the `main-3` copy. Both are the same commit; consolidating which copy runs is not part
  of this phase.
- A Cloudflare account id and the owner's own GitHub account label appear in a 7D work-order document
  (`2026-10-13-phase-7d-gpt-live-2-predeploy-config-result.md`). Neither is a credential, but both are
  account-identifying and are now in canonical history; worth a decision before any public push.

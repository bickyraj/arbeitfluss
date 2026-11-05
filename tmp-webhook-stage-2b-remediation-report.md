# FlowForge Milestone 9.6 — Stage 2B Remediation Report

Date: 2026-11-05. This report covers only remediation of F01, F03, and F04. Stage 2B still requires review/approval; Stage 3 has not begun.

## 1. Outcome and scope

| Finding | Final classification | Evidence |
|---|---|---|
| F01 — startup reconciliation locking | **FIXED** | Startup discovers IDs only; each workflow is locked and reloaded in a separate proxied transaction. Five startup integration tests pass on H2 and disposable PostgreSQL, including a coordinated concurrent writer/startup test |
| F03 — management error statuses | **FIXED** | Specific exception handlers return 400/405; five focused MockMvc tests verify statuses, Allow, authentication precedence, missing configuration, and generic 500 behavior |
| F04 — node-ID/path contract | **FIXED** | Webhook node IDs must match `[A-Za-z0-9_-]{1,255}` before persistence. Fifteen added test invocations cover generated IDs, unsafe characters, empty values, and length boundaries |
| F02/F02a — pre-existing active ingress and authorization/execution race | **NOT FIXED — inspect/report only as instructed** | No inbound controller, authentication exclusion, executor, graph, or execution-runtime behavior changed |
| F05 — broader verification gaps | **PARTIALLY FIXED** | PostgreSQL startup/save concurrency and remediation tests added; exhaustive concurrent rotation, cross-workflow negatives, and browser acceptance remain outside this change |
| F06 — malformed workflow aborting all startup reconciliation | **FIXED** for batch isolation | A malformed workflow fails its own transaction while a healthy workflow commits; the malformed definition itself is deliberately not repaired |
| F07 — non-idempotent shared-admin secret rotation | **NOT FIXED — unchanged behavior** | Explicit rotation still replaces the previous digest; this task does not add idempotency or multi-user identity |

Verification completed: **38 focused tests passed on H2; the same 38 passed on disposable PostgreSQL; all 123 backend tests passed; backend build passed; `git diff --check` passed.** No frontend file changed, so frontend tests/build were not rerun.

## 2. Audit selected and initial Git state

The current formal audit was `docs/reports/MILESTONE-9.6-VERIFICATION.md`. It was read and preserved unchanged. No current `tmp-webhook-recovery-report.md` was found or substituted for it.

Initial branch: `master`, tracking `origin/master`.
Initial HEAD: `433db95` (`updates`). Recent commits also included `970f111` and `0bfa286` (`updates`). There was no reported ahead/behind difference and nothing staged.

Initial unstaged tracked files:

- `backend/src/main/java/com/flowforge/controller/WebhookManagementController.java`
- `backend/src/main/java/com/flowforge/dto/webhook/WebhookRegistrationResponse.java`
- `backend/src/main/java/com/flowforge/dto/webhook/WebhookSecretResponse.java`
- `backend/src/main/java/com/flowforge/repository/WorkflowRepository.java`
- `backend/src/main/java/com/flowforge/service/WebhookRegistrationService.java`
- `backend/src/main/java/com/flowforge/service/WorkflowService.java`
- `docs/runtime/EXECUTION_ENGINE.md`
- `docs/runtime/WEBHOOK_TRIGGER.md`

Initial untracked files:

- `backend/src/test/java/com/flowforge/controller/WebhookRegistrationIntegrationTest.java`
- `docs/reports/MILESTONE-9.6-VERIFICATION.md`

All were preserved. The existing registration integration test and two already-modified production files were extended, not replaced with HEAD versions. No commits, pushes, resets, cleans, stashes, branch switches, or staging operations were performed.

## 3. Exact files changed by this remediation

This list describes the delta from the worktree at the start of this task, rather than incorrectly attributing earlier work to this remediation.

| File | Remediation change |
|---|---|
| `backend/src/main/java/com/flowforge/repository/WorkflowRepository.java` | Adds scalar ID discovery using a short read-only query; preserves the existing pessimistic locking query |
| `backend/src/main/java/com/flowforge/service/WebhookRegistrationService.java` | Adds per-workflow REQUIRES_NEW locked reconciliation; extracts the existing reconciliation body into a private helper; tightens webhook node-ID validation |
| `backend/src/main/java/com/flowforge/service/WebhookRegistrationStartupSync.java` | Discovers IDs without workflow snapshots; invokes the service through its proxy; isolates failures and emits only a generic warning |
| `backend/src/main/java/com/flowforge/exception/ApiExceptionHandler.java` | Adds safe 400 and 405 handlers with Allow support |
| `backend/src/test/java/com/flowforge/controller/WebhookRegistrationIntegrationTest.java` | Preserves the existing 13 tests and adds 15 node-ID test invocations |
| `backend/src/test/java/com/flowforge/controller/WebhookManagementErrorTest.java` | New: five focused error/authentication regression tests |
| `backend/src/test/java/com/flowforge/service/WebhookRegistrationStartupSyncTest.java` | New: five startup transaction/locking/lifecycle tests |
| `tmp-webhook-stage-2b-remediation-report.md` | New, self-contained remediation report |

Content-fingerprint comparison against the initial worktree confirmed that the formal audit, existing runtime documentation, Angular sources, authentication sources, execution sources, and migrations were not altered by this remediation. Only the seven application/test files above changed, plus this report.

## 4. F01 — root cause and fix

### Root cause

`WebhookRegistrationStartupSync` previously loaded all `Workflow` entities and synchronized those snapshots within one transaction. It never obtained the parent lock used by workflow updates/deletion and secret rotation. A workflow changed after discovery could therefore be reconciled using obsolete active/configuration state. One malformed legacy definition could also fail the whole startup batch.

### Exact protocol now used

1. `WorkflowRepository.findAllIdsForRegistrationSync()` selects only workflow UUIDs, ordered by ID, in a short read-only repository transaction. It does not load workflow entities or definition snapshots.
2. `WebhookRegistrationStartupSync.synchronizeSavedWorkflows()` has `NOT_SUPPORTED` propagation. It does not hold a batch transaction or persistence context containing stale workflows.
3. For each ID it calls the separate, injected `WebhookRegistrationService` bean's `synchronizeSavedWorkflow(UUID)` method.
4. That public service method has `REQUIRES_NEW`. Spring starts a new transaction and persistence context for each workflow.
5. Inside that transaction it uses the existing `findByIdForRegistrationUpdate()` query with `PESSIMISTIC_WRITE`. The current workflow is loaded only while acquiring the shared parent lock.
6. The locked/current workflow is passed to a private reconciliation helper. Registration updates and any sanitized definition update commit together while the parent lock is held.
7. A workflow deleted after discovery is skipped when the locked query returns empty.
8. A reconciliation/commit exception rolls back that workflow's transaction before returning through the service proxy. Startup catches it and proceeds to the next ID. It logs no workflow JSON, IDs, exception messages, stack traces, or credentials.

There is no reliance on self-invoked transactional annotations. The listener calls a different Spring bean; both public service entry points call the private implementation helper. Ordinary workflow saves retain their existing REQUIRED transaction, and ordinary update/delete/rotation still use their existing parent lock. No second locking mechanism was added.

Existing registrations are still looked up by `(workflow_id, node_id)` and updated in place. The reconciliation path neither regenerates public IDs nor hashes existing plaintext secrets. Mode transitions retain their pre-existing documented behavior: switching to NONE clears the digest; returning to Secret Token requires explicit generation.

### Actual F01 tests

`WebhookRegistrationStartupSyncTest` contains five passing tests:

- `startupUsesParentLockAndPreservesExistingPublicIdAndSecretHash`: verifies the existing locking query is called, full entity discovery is not used, the service is proxied, and ID/digest remain stable. Digest comparisons are internal booleans; values are not reported.
- `workflowDisabledAfterIdDiscoveryRemainsDisabledOnLockedReread`: disables the workflow after ID discovery and verifies that startup retains disabled workflow/registration state.
- `malformedWorkflowFailureIsIsolatedAndHealthyWorkflowCommitsDespiteCallerRollback`: places malformed and healthy fixtures in the isolated test database, reconciles the malformed ID first, and confirms the healthy registration commits even when an enclosing caller transaction is rolled back. The malformed definition is left unchanged.
- `workflowDeletedAfterDiscoveryIsSafelySkipped`: verifies missing workflows do not fail the batch or create registrations.
- `startupWaitsForConcurrentSaveAndReconcilesCommittedDisabledState`: uses separate threads and real transactions. A writer holds the existing pessimistic lock and flushes inactive workflow state. Startup starts while that lock is held and remains blocked; after the writer commits, startup obtains the lock and produces a disabled registration. The actual repository locking query is not stubbed out.

All five ran successfully against H2 and PostgreSQL 16. The PostgreSQL test is deterministic through latches and an explicit lock-holder transaction; it does not depend on probabilistic load or repeated races. A bounded timeout asserts that reconciliation cannot finish while the writer holds the lock.

## 5. F03 — root cause and fix

### Root cause

The shared advice's generic exception handler caught Spring's type-mismatch and unsupported-method exceptions and translated them into generic 500 responses.

### Exact fix

`ApiExceptionHandler` now handles:

- `MethodArgumentTypeMismatchException` with HTTP 400 and a fixed message, `Invalid request parameter`.
- `HttpRequestMethodNotSupportedException` with HTTP 405 and a fixed message, `HTTP method not supported`. Supported methods populate the standard Allow header when Spring supplies them.

For these handlers, the error path is the matched route template, or `/api` when no route matched. This avoids reflecting malformed path-parameter values in the response. Exception messages and supplied values are not echoed. The existing ErrorResponse structure is retained.

Unexpected exceptions continue to receive the existing generic 500 response. Authentication code is unchanged and runs before MVC error dispatch: missing/invalid bearer credentials still return 401, and missing server configuration still returns 503.

### Actual F03 tests

Five `WebhookManagementErrorTest` cases pass using MockMvc and the real filter/token service with test-only configuration:

- `malformedWorkflowUuidReturns400WithoutEchoingValue`.
- `unsupportedManagementMethodReturns405WithAllowHeader`.
- `missingAndIncorrectAuthenticationStillTakePrecedenceOverRouteErrors`, including verification endpoint success/failure.
- `missingServerConfigurationReturns503ForManagementAndVerification`.
- `unexpectedFailureRemainsGeneric500`, including non-disclosure of the diagnostic message.

These tests run in a MVC slice with mocked registration services; the missing-configuration case also uses a standalone MockMvc instance. No production credentials are used. The existing 31 administrator authentication tests also pass unchanged in the full suite.

## 6. F04 — root cause and fix

### Root cause and actual generated format

The frontend creates node IDs using:

```text
node-${Date.now()}-${Math.random().toString(16).slice(2, 6)}
```

Its existing seeded IDs also use forms such as `node-1`. Registration parsing previously allowed any nonblank string up to 255 characters, including slashes and route delimiters. Such IDs did not reliably fit the management API's single path segment.

### Exact fix

Webhook node IDs must now match `[A-Za-z0-9_-]{1,255}`. This preserves all observed frontend-generated formats and supports existing simple IDs such as `hook`. It rejects empty IDs, slashes, backslashes, whitespace, percent escapes, query/fragment delimiters, semicolons and dots. No ID is normalized or silently rewritten.

Validation uses a fixed, clear message and runs during definition sanitization before the first workflow flush. No migration or frontend generation change is required.

### Actual F04 tests

Fifteen added invocations in `WebhookRegistrationIntegrationTest` pass:

- Three safe/generated ID examples, including retrieval through the single-registration route.
- Eleven invalid forms, including empty, slash and backslash IDs, whitespace, encoded separators, delimiters and dot forms. Each verifies that workflow and registration row counts are unchanged after rejection.
- A boundary test accepts 255 characters, retrieves its metadata, rejects a 256-character update, and confirms the stored definition and registration retain the original 255-character ID.

### Compatibility implications

Persisted legacy webhook IDs outside the new character set are **not deleted or rewritten**. Such a workflow fails future save validation until an explicitly approved correction is made; startup skips its reconciliation without stopping healthy workflows. Existing registration state is retained rather than silently replaced.

This also means an active-only partial update of a workflow with an invalid legacy node ID can fail validation. A pre-existing enabled registration is not automatically disabled when the workflow is skipped. An operational review and an explicitly approved legacy-data correction plan are needed if such records exist. No query or write against the user's local database was performed to discover or alter them during this task. Ordinary editor-generated IDs remain compatible.

## 7. Verification commands and exact results

Backend commands ran from `backend/`. Every H2 invocation explicitly selected the existing isolated in-memory test URL/driver, H2 dialect, Hibernate create-drop and `SPRING_FLYWAY_ENABLED=false` to exclude the user's PostgreSQL database. Existing test-only token configuration was used; no credential values are included here.

### Focused H2 tests

```sh
./gradlew test \
  --tests com.flowforge.service.WebhookRegistrationStartupSyncTest \
  --tests com.flowforge.controller.WebhookManagementErrorTest \
  --tests com.flowforge.controller.WebhookRegistrationIntegrationTest
```

Final result: **38 passed, 0 failed/errors, 0 skipped** (5 startup + 5 error-status + 28 registration tests).

The first focused run had 37 passing invocations and one failed test waiting for the concurrency writer latch while a Spring Data repository spy was instrumented with a real-method call. Instrumentation was moved to the concrete service boundary, leaving the actual repository lock query intact. The corrected focused run, PostgreSQL run, and final full run all pass. The failure was not ignored or removed from the suite.

### Disposable PostgreSQL tests

The same three focused classes were rerun with `--rerun-tasks`, using:

- New container: `flowforge-stage2b-remediation-test`, PostgreSQL 16 Alpine.
- New database: `flowforge_stage2b_remediation`.
- Random loopback-only mapped port, 64812 for this run.
- No existing FlowForge database volume.
- PostgreSQL driver/dialect, Hibernate `validate`, and Flyway enabled **only for the disposable database**.

Result: **38 passed, 0 failed/errors, 0 skipped**. V1–V4 were successfully applied to that disposable database and their success status was confirmed before cleanup. The container was removed after verification.

### Full backend regression and build

```sh
./gradlew test --rerun-tasks
./gradlew build
```

Fresh full-suite XML totals: **123 tests, 123 passed, 0 failures, 0 errors, 0 skipped**. Build: **BUILD SUCCESSFUL**. The build reused the freshly passing test task and generated the backend artifacts. Compilation emits the pre-existing deprecated-API note for HttpRequestExecutor; no build error occurred.

| Class | Passed |
|---|---:|
| ExecutionApiIntegrationTest | 14 |
| ExecutionDispatchIntegrationTest | 3 |
| WebhookIntegrationTest (pre-existing) | 6 |
| WebhookManagementErrorTest | 5 |
| WebhookRegistrationIntegrationTest | 28 |
| WorkflowCrudIntegrationTest | 2 |
| ConditionEvaluatorTest | 3 |
| HttpRequestExecutorTest | 16 |
| OutboundAddressPolicyTest | 1 |
| WorkflowExecutionWorkerTest | 1 |
| AdminApiAuthenticationTest | 31 |
| ExecutionRecoveryServiceTest | 1 |
| ExecutionStatusTest | 2 |
| WebhookRateLimiterTest | 1 |
| WebhookRegistrationStartupSyncTest | 5 |
| WorkflowGraphValidatorTest | 4 |
| **Total** | **123** |

`git diff --check`: passed. Frontend tests/build: not rerun, because no frontend source or configuration changed in this remediation. No manual browser acceptance was claimed.

## 8. Database and migration safety

The user's existing local FlowForge PostgreSQL database was **not queried, migrated, or modified during this remediation**. PostgreSQL commands addressed only the newly created remediation container/database. H2 tests used a separate in-memory database. No production credentials were used or printed.

V1, V2, V3 and V4 migration files are unchanged, confirmed by content comparison with the initial worktree and Git diff. No migration was added. Applying existing migrations to the disposable test database was verification only; no migration command targeted the existing database.

The previous formal audit report is unchanged. Existing authentication, executor, graph-validator, execution-service/runtime, and Angular sources match their pre-remediation content.

## 9. Regression protection

- Stage 2A authentication: source unchanged; all 31 existing authentication cases and five new status/authentication cases pass.
- Management API/metadata contracts: controller and DTO sources unchanged by this remediation; existing registration API tests pass.
- Hash-only persistence, one-time reveal, stable IDs/digests and rotation: existing registration tests pass; startup stability is additionally tested on both databases.
- Activation and definition preservation: `activationOnlyUpdatePreservesDefinitionAndStableRegistration` passes; the existing frontend partial-update implementation is unchanged.
- Manual execution/snapshots/history/node records/logs: existing execution API tests pass.
- Async dispatch, cancellation and timeout: existing dispatch tests pass.
- IF/ELSE routing, branch skipping/rejoining, graph traversal and data passing: existing graph/evaluator/execution tests pass.
- Retry, recovery and transitions: existing reliability tests pass.
- HTTP Request functionality/security: all 16 executor tests and the address-policy test pass.

No runtime/executor/graph behavior was changed to obtain these results. Claims are limited to the exercised cases; they are not exhaustive production or distributed-system acceptance.

## 10. Stage boundary: actual ingress status — inspection only

### Registered components and routes

The existing component scan registers:

- `WebhookController` (`@RestController`), mapping `/api/webhooks/{webhookId}` with an unrestricted `@RequestMapping` method declaration. Its code checks the configured method and can call `ExecutionService.startWebhookExecution()`.
- `WebhookTriggerExecutor` (`@Component`), registered under `webhook-trigger` in the existing NodeExecutorRegistry.
- `WebhookRateLimiter` (`@Component`), the existing single-instance ingress limiter.
- `WebhookRegistrationService`, registration repository, management controller, and startup synchronizer.
- Existing ExecutionService WEBHOOK creation, WEBHOOK enum value, and graph trigger support already present before this task.

There is no acceptance feature flag separating this earlier ingress implementation from Stage 2B registration functionality.

### Administrator-filter exclusions

The filter is unchanged. It permits without administrator bearer authentication:

- Exact `POST /api/auth/verify`; the verification controller still validates the bearer header itself.
- Exact `POST /api/webhooks/{webhookId}` where the ID is 1–64 URL-safe letters/digits/underscores/hyphens.
- OPTIONS, for existing CORS/MVC handling.

Nested management paths under `/api/workflows/{workflowId}/webhooks...` require administrator authentication. Non-POST inbound methods also require it, even though the existing inbound controller can dispatch those methods when the registration's configured method matches. Paths outside `/api/**` retain their existing authentication behavior.

### Can the application accept requests before formal Stage 3 acceptance?

**Yes, the pre-existing code is active when the application runs.** With an enabled, valid registration, a matching POST and the required webhook secret (or NONE authentication), the existing handler can create/dispatch an execution. The six existing WebhookIntegrationTest cases still exercise earlier code. This is a statement of current code/test behavior, **not approval or completion of Stage 3**.

This remediation neither removed nor disabled those beans and did not change inbound runtime behavior. It did not start the application against the user's database.

### Smallest safe distinction and deployment risk

Immediately distinguish acceptance status in release/deployment notes: registration/persistence has been remediated, while ingress is implemented-but-unaccepted and may already be reachable. Documentation alone is not a runtime isolation control.

If a registration-only deployment must actually reject all inbound deliveries, the smallest prospective technical control is an explicitly approved configuration gate on the inbound controller/route, preserving the implementation while disabling its mapping by default. **No gate was added or changed here.** That would alter runtime behavior and requires a separate user decision before implementation.

Leaving ingress active exposes the existing authentication-to-execution race: the handler resolves/authenticates a registration, then starts an execution using only the workflow ID in another transaction. Rotation, node replacement or disablement in between may leave authorization based on stale registration state. F01's startup fix does not fix that separate in-flight race. Input parsing, rate limiting, replay/idempotency and full asynchronous acceptance remain Stage 3 review items. Avoid treating this remediation or a passing build as permission to expose the endpoint in production.

## 11. Remaining limitations and unverified behavior

- Stage 2B has not been formally reapproved; Stage 3 is not authorized by this report.
- F02/F02a ingress activation and its in-flight lifecycle race remain unchanged by instruction.
- The real PostgreSQL concurrency test covers concurrent inactive workflow save versus startup lock/reread. It is not exhaustive multi-instance stress testing or concurrent secret-rotation acceptance.
- Malformed legacy workflows, including previously accepted unsafe node IDs, are skipped and retained; no data repair, deletion or automatic disabling is attempted. Their existence in the user's database was not checked here.
- Startup ID enumeration still fails if the database itself cannot be queried; per-workflow isolation cannot reconcile data from an unavailable database. There is no retry scheduler added for skipped malformed workflows.
- The new 400/405 handlers avoid reflecting invalid values. Other pre-existing advice handlers and broader JSON binding-error redaction were not refactored.
- Secret rotation remains non-idempotent and shared-admin; exactly-once delivery and per-user identity are not provided.
- Cross-workflow resource-negative coverage, browser workflow/secret-storage acceptance and a complete Stage 3/Stage 4 audit remain outstanding.

## 12. Final Git state

Branch remains `master`, tracking `origin/master`; HEAD remains `433db95`. Nothing is staged or committed.

There are ten modified tracked files: the eight initial Stage 2B files, plus ApiExceptionHandler and WebhookRegistrationStartupSync. The untracked registration integration test is preserved and extended. The two new remediation test files and this report are added alongside the unchanged untracked formal audit.

The exact final porcelain status is included below. Its existing changes are not all newly authored by this remediation; section 3 is the exact remediation delta.

```text
## master...origin/master
 M backend/src/main/java/com/flowforge/controller/WebhookManagementController.java
 M backend/src/main/java/com/flowforge/dto/webhook/WebhookRegistrationResponse.java
 M backend/src/main/java/com/flowforge/dto/webhook/WebhookSecretResponse.java
 M backend/src/main/java/com/flowforge/exception/ApiExceptionHandler.java
 M backend/src/main/java/com/flowforge/repository/WorkflowRepository.java
 M backend/src/main/java/com/flowforge/service/WebhookRegistrationService.java
 M backend/src/main/java/com/flowforge/service/WebhookRegistrationStartupSync.java
 M backend/src/main/java/com/flowforge/service/WorkflowService.java
 M docs/runtime/EXECUTION_ENGINE.md
 M docs/runtime/WEBHOOK_TRIGGER.md
?? backend/src/test/java/com/flowforge/controller/WebhookManagementErrorTest.java
?? backend/src/test/java/com/flowforge/controller/WebhookRegistrationIntegrationTest.java
?? backend/src/test/java/com/flowforge/service/WebhookRegistrationStartupSyncTest.java
?? docs/reports/MILESTONE-9.6-VERIFICATION.md
?? tmp-webhook-stage-2b-remediation-report.md
```

F01, F03 and F04 are FIXED with executed test evidence. No Stage 3 implementation or ingress-behavior change was made. Stop here for review and explicit approval.

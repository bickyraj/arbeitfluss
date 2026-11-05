# FlowForge Milestone 9.6 — Stage 3: Webhook Ingress and Execution Hardening

Date: 2026-11-05. Branch: `master`, tracking `origin/master`. HEAD: `433db95` (`updates`). No commits, pushes, staging, resets, cleans, stashes, or branch switches were performed.

## 1. Completion status

**Stage 3 is implementation-complete for the requested scope. F02a is fixed with transaction/locking and regression-test evidence. No implementation blockers remain for this scope.** Deployment enablement and the limitations below remain explicit operator/review responsibilities. Stage 4 has not begun.

The existing ingress path now reauthorizes against the current registration and workflow while holding the same parent-workflow lock used by registration saves, startup reconciliation, deletion and secret rotation. The resulting execution snapshot, execution record, node records and initial log are persisted in that transaction and dispatched through the existing after-commit runtime.

Ingress has an explicit **default-off deployment gate**. Operators must set `FLOWFORGE_WEBHOOK_INGRESS_ENABLED=true` in the backend launch environment and restart to accept deliveries. Registration management and manual execution remain available with the gate off.

Verification: one focused PostgreSQL run executed 40 cases, with 39 passing and one invalid stream test fixture failing. The fixture was corrected and only the affected test was rerun successfully. The subsequently required full backend suite/build ran once: **156 tests passed, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL**. `git diff --check` passed. No passing focused suite or full suite was unnecessarily rerun.

## 2. Starting state and preserved work

The starting point was the existing implementation, `docs/runtime/WEBHOOK_TRIGGER.md`, `docs/runtime/EXECUTION_ENGINE.md`, and `tmp-webhook-stage-2b-remediation-report.md`. The Stage 2B audit was not repeated and architecture discovery was not restarted.

The initial worktree was not clean: it contained ten modified tracked Stage 2B/remediation files, three untracked Stage 2B test files, the formal audit and the Stage 2B remediation report. All were preserved. Where Stage 3 extends an already-modified file, it extends the existing worktree content rather than restoring a committed version.

Stage 2A authentication source, the frontend login fix, Stage 2B management controller/DTO contracts, WorkflowService, WorkflowRepository's parent lock, startup locking fix, F03 error handlers, F04 node-ID validation, graph validator and runtime/executor implementations are preserved. Existing runtime documentation is updated to describe Stage 3; prior audit/remediation reports are not overwritten.

## 3. F02a root cause and resolution

### Root cause

The old controller resolved an enabled registration and authenticated its detached entity, then called `ExecutionService.startWebhookExecution(workflowId, triggerInput)`. Those operations used different transactions. The execution entry point knew only the workflow ID, so it could not detect a registration rotation, disablement, deletion, method change, authentication change, node removal or replacement between authentication and execution creation.

### Current admission protocol

`WebhookController.receive()` performs cheap preliminary checks and bounded parsing. That detached state is never used as the authorization decision for persistence.

`ExecutionService.startWebhookExecution()` now accepts the public webhook ID, HTTP method, presented secret and parsed trigger input. The unsafe workflow-ID-only webhook entry point has been replaced.

The new entry point is a proxied `REQUIRES_NEW` transaction:

1. Check the deployment gate and the 43-character backend-generated public ID format.
2. Discover only the parent workflow UUID using `WebhookRegistrationRepository.findWorkflowIdForIngress()`. This scalar query does not preload a registration or workflow into the persistence context.
3. Acquire the existing `WorkflowRepository.findByIdForRegistrationUpdate()` pessimistic parent-workflow lock and load the current workflow state.
4. Reread the registration by public ID after acquiring the lock.
5. Call the shared `WebhookRegistrationService.authorizeIngress()` checks on this current registration and locked workflow. The registration must still belong to that workflow, be enabled, have an active workflow and active matching webhook node, and have configuration consistent with the saved method/authentication mode. Secret Token mode must have a stored digest and a valid presented secret.
6. Validate the current graph and require its root node ID to equal the registration's node ID.
7. Use the existing execution creation path to snapshot the current workflow and trigger input and persist execution/node/log records. The lock is held through commit.
8. Use the existing after-commit dispatcher, worker, graph runtime, lifecycle and failure handling. No second runtime or application is introduced.

Network reading/parsing stays outside the parent lock, so a slow request body does not hold a workflow mutation lock. Preliminary authentication helps reject bad requests cheaply, but is always repeated inside the authoritative transaction.

All supported workflow/registration mutation paths use the same parent lock. A mutation that commits first is visible to authoritative admission; a mutation arriving after admission takes the lock must wait until execution creation commits. The tests cover both ordering directions.

A later rotation or deactivation does not cancel an execution already accepted before that mutation. Unrelated workflow edits that leave the current registration valid can be accepted using the current snapshot; a preliminary snapshot is never substituted for the current definition.

## 4. Request validation and security behavior

### Identity, state and authentication

- Only generated 43-character URL-safe public IDs are accepted by the controller/service. The Stage 2A filter's existing exact public-route matching is unchanged.
- Unknown, removed, disabled, inconsistent or missing registrations fail closed with 404.
- Workflow/node inactivity, node removal/replacement and registration/workflow mismatch cannot create executions.
- The configured method is checked both preliminarily and under the lock. Mismatches return 405 with the configured method in Allow.
- Secret Token authentication reads one `X-FlowForge-Webhook-Secret` header. Missing, incorrect or duplicate headers return 401. Authentication is not read from query parameters, cookies or JSON.
- SHA-256 digest comparison uses the existing `MessageDigest.isEqual` implementation. No plaintext secret or digest is added to workflow definitions or execution snapshots.
- Registration authentication mode and method must agree with the matching current node. Invalid execution graphs are rejected before persistence.
- Validation responses are opaque; unexpected failures use existing generic server-error handling. No new code logs secrets, sensitive headers or request bodies.

The administrator filter is unchanged: exact POST ingress is public with respect to the administrator token; GET/PUT/PATCH/DELETE ingress methods still require that bearer token in addition to any configured webhook secret. OPTIONS retains the existing CORS/MVC handling. All webhook management routes remain administrator protected.

### Bounded request input

The valid input contract is retained: `body`, `query` arrays, allowlisted `headers`, `method`, and server-generated `receivedAt`.

- Declared bodies over 1 MiB are rejected before stream access.
- Unknown-length streams read at most 1 MiB + 1 byte and reject over-limit bodies before parsing. The extra byte detects overflow.
- The final serialized input envelope must also fit 1 MiB. A raw body within the byte limit can still be too large after JSON wrapping/escaping.
- Supported concrete content types are application/json, concrete application subtypes ending in +json, and text/plain, using UTF-8. Wildcards, unsupported types/charsets and invalid UTF-8 return 400.
- Malformed JSON, trailing JSON content, duplicate JSON keys, excessive nesting and excessive JSON string sizes are rejected. The existing depth limit is 50 and JSON string limit is 256 KiB.
- Empty requests retain a null body. Text/plain retains a string body.
- Query string limit remains 8 KiB; parameter count/value/name limits and the final envelope cap remain enforced. A cheap character-length check avoids allocating an unbounded UTF-8 byte copy of an already oversized query string.
- Only content-type, user-agent and x-request-id are copied, with existing per-value bounds. Authorization, cookies, the webhook secret and arbitrary sensitive/custom headers are excluded.

Body/query data remains user data persisted in history. Administrators should not put secrets or unnecessary personal information in those fields or in allowlisted headers.

### Response behavior

| Status | Meaning |
|---|---|
| 202 | One execution committed and admitted to the existing asynchronous path |
| 400 | Invalid payload/type/encoding or graph |
| 401 | Invalid/missing/duplicate secret; existing administrator requirements also apply to non-POST ingress |
| 404 | Gate off, malformed supported public-ID shape, missing/disabled/deleted endpoint, or inconsistent node/registration |
| 405 | Configured method mismatch; Allow is supplied |
| 413 | Body/query/envelope exceeds limits |
| 429 | Local limiter rejects the attempt |
| 500 | Existing generic unexpected-server-error behavior |

The existing administrator filter may reject malformed paths outside its public exception shape before controller-level ID validation. This security policy was deliberately preserved.

## 5. Limiter and delivery semantics

The existing synchronized, bounded in-memory limiter remains the limiter implementation. Its application point is after a known enabled registration lookup and before secret validation/body parsing. Incorrect-secret and wrong-method attempts consume the budget. Disabled/unknown IDs do not allocate limiter entries.

Budget: 60 attempts per 60-second fixed window per public webhook ID/socket remote address. State is capped at 10,000 keys, expires on reclamation, and resets on restart. Forwarded request headers are not used to establish source identity. New tests verify expiration/capacity recovery and a concurrent per-key budget.

The limiter is single-instance, not Redis-backed or distributed. Unknown-ID lookups are not covered by the per-registration budget; upstream network controls remain appropriate for abusive traffic. Configure trusted proxy boundaries and server read/connection timeouts. These deployment controls are not a second execution mechanism.

Every accepted delivery creates exactly one execution through the existing persistence/dispatch path. Repeating a valid delivery creates another execution. This is not exactly-once delivery, deduplication, replay prevention or idempotency. Existing dispatcher failure recording and recovery semantics remain unchanged; a 202 acknowledgement is not a guarantee that downstream workflow work succeeded.

## 6. Deployment gate and operator workflow

New setting: `flowforge.webhooks.ingress-enabled`.
Environment binding: `FLOWFORGE_WEBHOOK_INGRESS_ENABLED`.
**Production default: false.** Test configuration enables ingress explicitly for integration testing; gate-specific tests override it to false.

To enable ingress, set the environment variable to true in the environment that launches the backend and restart it. The gate does not change the administrator token, registration metadata, registration identity, stored hashes or the management endpoints. Both HTTP ingress and the authoritative service entry point enforce the gate.

This default is intentional: earlier inbound code was implemented but not accepted, and registration provisioning must not silently make it reachable for delivery. Existing deployments that relied on that earlier path now require explicit operator opt-in; the change is prominently documented in `docs/runtime/WEBHOOK_TRIGGER.md`.

A registration can report enabled=true while the deployment gate is off. That metadata retains its Stage 2B per-registration meaning. Stage 4 must make the UI's gate visibility and public URL handling explicit. Already admitted executions and administrator-triggered manual operations are not cancelled by turning ingress off.

## 7. Test execution and exact results

### Focused run — disposable PostgreSQL

Executed once from `backend/`:

```sh
./gradlew test \
  --tests com.flowforge.controller.WebhookIngressHardeningTest \
  --tests com.flowforge.controller.WebhookBodyLimitTest \
  --tests com.flowforge.controller.WebhookIngressGateTest \
  --tests com.flowforge.service.WebhookRateLimiterTest \
  --tests com.flowforge.controller.WebhookIntegrationTest
```

Process overrides selected only the new `flowforge_stage3_test` database in container `flowforge-stage3-test`, exposed on a random loopback port (64939 for this run). PostgreSQL driver/dialect, Hibernate validate and Flyway enabled were used only for this disposable database. No existing FlowForge volume/database was used.

Initial result: **40 cases executed; 39 passed, 1 failed**. The failed unknown-length test fixture incorrectly returned EOF for a zero-byte `InputStream.read` call. That broke the InputStream contract and made the upload appear truncated, producing 400 rather than the expected over-limit 413. The fixture was corrected to return zero for a zero-byte read; production stream handling and test assertions were not weakened.

Only the affected test was rerun:

```sh
./gradlew test --tests 'com.flowforge.controller.WebhookBodyLimitTest.unknownLengthStreamReadsAtMostLimitPlusOneBytes'
```

Affected-test retry: **1 passed, 0 failed**. It confirms exactly 1 MiB + 1 byte consumed and no execution call. No already-passing focused suite was rerun.

The 25 hardening cases all passed in the PostgreSQL run, including deterministic mutations after preliminary authentication and the real parent-lock concurrency test. The disposable container was removed after focused verification.

### Full backend suite and build — isolated H2

Executed once:

```sh
./gradlew test build
```

Process overrides forced the existing in-memory H2 test URL/driver/dialect, Hibernate create-drop and Flyway disabled. This run freshly executed all tests, rather than reusing the targeted test selection.

Result: **156 passed, 0 failures, 0 errors, 0 skipped; BUILD SUCCESSFUL**. The full suite includes every focused test with its corrected fixture.

| Test class | Passed |
|---|---:|
| ExecutionApiIntegrationTest | 14 |
| ExecutionDispatchIntegrationTest | 3 |
| WebhookBodyLimitTest | 4 |
| WebhookIngressGateTest | 2 |
| WebhookIngressHardeningTest | 25 |
| WebhookIntegrationTest | 6 |
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
| WebhookRateLimiterTest | 3 |
| WebhookRegistrationStartupSyncTest | 5 |
| WorkflowGraphValidatorTest | 4 |
| **Total** | **156** |

`git diff --check`: passed. No frontend files changed, so frontend tests/build were not required or rerun.

### Important regression coverage

`mutationAfterPreliminaryAuthenticationCannotUseStaleAuthorization` covers rotation, workflow disablement, node inactivity, node replacement/removal, workflow deletion, method changes and authentication-related configuration changes. Test-only body-read hooks mutate state after preliminary checks but before authoritative execution admission. Every rejected request leaves the execution count unchanged.

`switchingPublicRegistrationToSecretDuringUploadRequiresNewSecret` verifies that an originally unauthenticated request cannot reuse the earlier public mode after the current configuration requires a secret.

`authorizationAndSnapshotHoldParentLockUntilExecutionCommit` pauses after authoritative authorization during graph validation, starts a concurrent disabling save, verifies that the mutation cannot complete while admission holds the lock, then checks the committed execution's immutable definition/input and the later disabled workflow state. This passed with real PostgreSQL row locks.

`acceptedDeliveriesCreateOneExecutionEachAndKeepSanitizedInput` verifies two deliveries create two, not duplicated or deduplicated, execution records, preserve the input contract, exclude credential headers, and complete through the existing worker/runtime.

Gate tests prove that disabled ingress prevents both HTTP/direct admission but preserves management metadata and manual execution. Streaming tests prove early declared-size rejection, bounded unknown-length reads, final-envelope limits and opaque IO failure responses.

The full suite retains passing Stage 2A, Stage 2B and remediation tests and existing manual snapshots/history/logs, asynchronous dispatch, IF/ELSE/data-flow/rejoin, cancellation, timeout, retry, recovery and HTTP Request protections.

## 8. Database and migration impact

**No migration file was created or changed. V1–V4 are preserved. No migration was necessary.** Existing indexes support the scalar public-ID/parent lookup and locking queries.

The user's existing local PostgreSQL database was **not accessed or modified**, including no read-only queries. PostgreSQL tests ran only in the newly created disposable container/database, without an existing volume. Existing migrations were applied only there as part of validation. The test container was removed afterward.

The full suite used isolated in-memory H2. No production credentials or actual secret/hash values are included in this report or intentionally logged by new code.

## 9. Exact Stage 3 file changes

This is the delta from the worktree at Stage 3 start, not a list attributing earlier uncommitted work to Stage 3.

| File | Change |
|---|---|
| `backend/src/main/java/com/flowforge/controller/WebhookController.java` | Gate, shared preliminary checks, bounded strict parsing and atomic service handoff; opaque ingress responses |
| `backend/src/main/java/com/flowforge/repository/WebhookRegistrationRepository.java` | Scalar parent-workflow lookup without entity preloading |
| `backend/src/main/java/com/flowforge/service/ExecutionService.java` | Replaces the workflow-ID-only handoff with current locked authorization, root-node binding and existing execution creation |
| `backend/src/main/java/com/flowforge/service/WebhookRegistrationService.java` | Shared current workflow/node/registration/method/secret validation helper; preserves all Stage 2B/remediation behavior |
| `backend/src/main/java/com/flowforge/service/WebhookIngressSettings.java` | New shared deployment gate bean |
| `backend/src/main/java/com/flowforge/exception/WebhookRequestRejectedException.java` | New opaque status/Allow rejection result across the transaction boundary |
| `backend/src/main/resources/application.yml` | Default-off operator environment binding |
| `backend/src/test/resources/application-test.yml` | Explicit test-only ingress enablement |
| `backend/src/test/java/com/flowforge/controller/WebhookIngressHardeningTest.java` | New authentication/state races, parsing, limiter, input, execution and locking tests |
| `backend/src/test/java/com/flowforge/controller/WebhookBodyLimitTest.java` | New bounded-stream/envelope/IO tests |
| `backend/src/test/java/com/flowforge/controller/WebhookIngressGateTest.java` | New gate/management/manual-execution tests |
| `backend/src/test/java/com/flowforge/service/WebhookRateLimiterTest.java` | Expiry/capacity and concurrent budget regression tests |
| `docs/runtime/WEBHOOK_TRIGGER.md` | Stage 3 behavior, enablement, limits, statuses and limitations, preserving management/lifecycle documentation |
| `docs/runtime/EXECUTION_ENGINE.md` | Atomic webhook admission and shared runtime documentation |
| `tmp-flowforge-stage-3-report.md` | This fresh self-contained report |

Content comparison against the start-of-task baseline confirms the formal audit, Stage 2A authentication/Angular sources, migrations, F01 startup fix, F03 exception handlers, F04 ID validation, and other unrelated work were not overwritten. The expected Stage 3 helper addition in the registration service does not replace its existing reconciliation/validation code.

## 10. Remaining limitations and review items

- Ingress is disabled until an operator explicitly enables it. No production application was launched or changed by the agent.
- The limiter is per-instance and bounded, not a distributed global budget. Unknown-ID lookup floods need upstream traffic controls. Trusted proxy and read/connection timeout configuration remain operator responsibilities.
- No exactly-once delivery, replay protection, deduplication, idempotency or guaranteed downstream success is provided.
- POST alone is exempt from administrator bearer authentication; non-POST deliveries retain the Stage 2A administrator requirement. This is intentional compatibility/security preservation.
- The shared administrator token remains a single principal with no per-user identity/roles or individual revocation.
- Lock safety assumes registration/workflow mutations follow the supported parent-lock protocol. Arbitrary external SQL writers are not coordinated by application conventions.
- The tested interleavings are deterministic stale-check mutations and a real row-lock serialization case, not exhaustive multi-instance load/stress testing.
- Legacy malformed workflows/unsafe node IDs remain governed by Stage 2B remediation behavior: skip or reject without silently rewriting/deleting existing data. No live data audit was performed.
- Existing frontend metadata reflects per-registration readiness, not global gate status. Full frontend gate visibility, public URLs and configuration acceptance remain Stage 4 work.
- Browser-to-deployed-server/TLS/reverse-proxy acceptance was not performed. Automated tests exercise real Spring transactions, HTTP-controller handling and the existing asynchronous runtime in isolated environments.

These are explicit operational or later-stage boundaries, not concealed implementation blockers for the requested Stage 3 hardening.

## 11. Final Git state

Branch remains `master`, tracking `origin/master`; HEAD remains `433db95`. Nothing is staged or committed. Existing uncommitted Stage 2B/remediation work and reports are retained alongside the Stage 3 changes.

```text
## master...origin/master
 M backend/src/main/java/com/flowforge/controller/WebhookController.java
 M backend/src/main/java/com/flowforge/controller/WebhookManagementController.java
 M backend/src/main/java/com/flowforge/dto/webhook/WebhookRegistrationResponse.java
 M backend/src/main/java/com/flowforge/dto/webhook/WebhookSecretResponse.java
 M backend/src/main/java/com/flowforge/exception/ApiExceptionHandler.java
 M backend/src/main/java/com/flowforge/repository/WebhookRegistrationRepository.java
 M backend/src/main/java/com/flowforge/repository/WorkflowRepository.java
 M backend/src/main/java/com/flowforge/service/ExecutionService.java
 M backend/src/main/java/com/flowforge/service/WebhookRegistrationService.java
 M backend/src/main/java/com/flowforge/service/WebhookRegistrationStartupSync.java
 M backend/src/main/java/com/flowforge/service/WorkflowService.java
 M backend/src/main/resources/application.yml
 M backend/src/test/java/com/flowforge/service/WebhookRateLimiterTest.java
 M backend/src/test/resources/application-test.yml
 M docs/runtime/EXECUTION_ENGINE.md
 M docs/runtime/WEBHOOK_TRIGGER.md
?? backend/src/main/java/com/flowforge/exception/WebhookRequestRejectedException.java
?? backend/src/main/java/com/flowforge/service/WebhookIngressSettings.java
?? backend/src/test/java/com/flowforge/controller/WebhookBodyLimitTest.java
?? backend/src/test/java/com/flowforge/controller/WebhookIngressGateTest.java
?? backend/src/test/java/com/flowforge/controller/WebhookIngressHardeningTest.java
?? backend/src/test/java/com/flowforge/controller/WebhookManagementErrorTest.java
?? backend/src/test/java/com/flowforge/controller/WebhookRegistrationIntegrationTest.java
?? backend/src/test/java/com/flowforge/service/WebhookRegistrationStartupSyncTest.java
?? docs/reports/MILESTONE-9.6-VERIFICATION.md
?? tmp-flowforge-stage-3-report.md
?? tmp-webhook-stage-2b-remediation-report.md
```

Stage 3 implementation is complete for the requested scope, with no implementation blockers identified. Deployment remains opt-in. Stop here for review; do not begin Stage 4 automatically.

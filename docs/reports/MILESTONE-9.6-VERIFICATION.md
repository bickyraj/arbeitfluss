# FlowForge Milestone 9.6 — Stage 2B Formal Verification Audit

Audit date: 2026-11-05. Baseline: `master`, commit `433db95`, plus the existing uncommitted Stage 2B changes.

## 1. Executive Summary

**Overall classification: VERIFIED WITH LIMITATIONS; approval withheld pending the lifecycle concurrency issue and stage-boundary review below.**

Persistent registration, ordinary workflow reconciliation, safe management metadata, backend-generated public IDs, secret generation/rotation, and transaction rollback are implemented and have meaningful passing tests. Fresh audit execution completed **98 backend tests: 98 passed, 0 failed, 0 skipped**, followed by a successful backend build. An additional database-free probe verified independent reconciliation and stable identity for multiple webhook nodes. Frontend audit results are recorded in section 11.

Read-only inspection of the existing local PostgreSQL database confirmed that **V4 — create webhook registrations — is applied successfully**. The live columns, constraints, cascade, and indexes match the registration migration and entity structure. The live table contained **zero registrations**, so it cannot establish live lifecycle behavior. No migrations or PostgreSQL writes were performed during this audit.

Important findings:

1. **F01 — High, FAILED: startup reconciliation bypasses the parent-workflow locking protocol.** Ordinary workflow updates/deletion and secret rotation lock the workflow, but `WebhookRegistrationStartupSync` reads every workflow and calls synchronization without that lock. A stale startup snapshot can overwrite newer registration state, including re-enabling a node disabled by a concurrent save. No concurrent PostgreSQL reproduction was attempted because database mutation is outside this audit.
2. **F02 — High stage-boundary concern, VERIFIED WITH LIMITATIONS: pre-existing Stage 3-scoped code remains registered without an acceptance gate.** The repository already contains a mapped inbound controller and execution integration. Documentation correctly calls it unaccepted earlier work; that statement does not disable the code. Its lifecycle authorization/execution race remains unresolved. This audit does not accept that code as completed Stage 3 functionality.
3. **F03 — Medium, FAILED: invalid workflow UUIDs and unsupported management HTTP methods return 500.** Database-free MockMvc probes reproduced both results. The shared catch-all exception handler intercepts errors that should normally become 400 and 405.
4. **F04 — Low, VERIFIED WITH LIMITATIONS: accepted node IDs exceed the management URL contract.** Registration validation accepts a node ID containing `/`, while management endpoints address nodes through a single path segment. Acceptance was reproduced; deployed encoded-slash behavior was not tested.
5. **F05 — Verification gap: concurrent saves/rotation/startup, resource ownership negatives, and several lifecycle edge cases lack dedicated database-backed tests.** Passing single-threaded tests do not prove these cases.

No application source, administrator authentication, existing documentation, migrations, or existing Git changes were modified by this audit. The only new repository content produced by the audit is this report. Test/build artifacts were regenerated. Backend integration tests used isolated in-memory H2 with Flyway explicitly disabled; existing PostgreSQL access used explicit read-only transactions. Temporary probe helpers were kept outside the repository.

## 2. Git and Repository State

| Item | Observed state before audit |
|---|---|
| Branch | `master`, tracking `origin/master`; no reported ahead/behind difference |
| HEAD | `433db95` — `updates` |
| Staged changes | None |
| Unstaged tracked changes | Eight files, listed in section 3 |
| Untracked implementation files | `backend/src/test/java/com/flowforge/controller/WebhookRegistrationIntegrationTest.java` |
| Branches | Local `master`; remote `origin/master` and `origin/HEAD` |
| Backup branches/stashes | None listed |
| Recent commits | `433db95`, `970f111`, `0bfa286`, `76a1df9`, `63ec851`, `4f9526c`; generic subjects do not establish milestone acceptance |

**Stage 2A intact — VERIFIED.** There are no Stage 2B diffs in `AdminTokenService`, `AdminApiAuthenticationFilter`, or `ApiAuthController`. The frontend authentication service sends the verification bearer header explicitly, with a null body, and retains authentication in a signal rather than browser persistence. The administrator authentication test class ran successfully in this audit.

**Stage 2B present — VERIFIED.** The current working diff contains registration-service hardening, workflow locking and sanitization, management API/DTO additions, documentation, and the new registration test class. The V4 migration, registration entity/repository, and baseline synchronization already existed in `433db95`.

**Important provenance:** `433db95` also includes the earlier `WebhookController`, `WebhookTriggerExecutor`, `WEBHOOK` enum addition, graph support, rate limiter, execution integration, and frontend webhook UI. These are not uncommitted Stage 2B additions. They were preserved, not removed or accepted as later-stage completion.

No commits, staging, branch changes, reset, clean, restore, or stash operations were performed. The final working tree retains all pre-audit changes and adds this report. A before/after content-fingerprint comparison of 150 application and existing documentation files found zero audit-induced changes. No credential values were included in that comparison output.

## 3. Files Changed During Stage 2B

The exact current Stage 2B implementation delta from `433db95` is:

| File | Change |
|---|---|
| `backend/src/main/java/com/flowforge/controller/WebhookManagementController.java` | Single-registration GET; secret response includes safe metadata and `Cache-Control: no-store` |
| `backend/src/main/java/com/flowforge/dto/webhook/WebhookRegistrationResponse.java` | Creation/update timestamps |
| `backend/src/main/java/com/flowforge/dto/webhook/WebhookSecretResponse.java` | Safe registration metadata alongside the one-time plaintext field |
| `backend/src/main/java/com/flowforge/repository/WorkflowRepository.java` | Pessimistic parent-workflow locking query |
| `backend/src/main/java/com/flowforge/service/WebhookRegistrationService.java` | Pre-persistence sanitization/validation, duplicate-ID checks, single metadata lookup, locked rotation, transactional deletion helper |
| `backend/src/main/java/com/flowforge/service/WorkflowService.java` | Sanitization before workflow flushing; locked update/delete |
| `backend/src/test/java/com/flowforge/controller/WebhookRegistrationIntegrationTest.java` | New, untracked; 13 integration tests |
| `docs/runtime/WEBHOOK_TRIGGER.md` | Stage 2B contracts, lifecycle, security, schema, and stage limitations |
| `docs/runtime/EXECUTION_ENGINE.md` | Registration architecture and explicit later-stage acceptance boundary |

There are **no Stage 2B changes** to Angular files, authentication, execution runtime, execution service, trigger enum, executor implementations, graph validator, or migrations. The audit report itself is an audit output, not part of the Stage 2B implementation delta.

## 4. Database and Flyway Migration Audit

### 4.1 Migration/entity structure — VERIFIED

Inspected `backend/src/main/resources/db/migration/V4__create_webhook_registrations.sql`, `WebhookRegistration.java`, and `WebhookRegistrationRepository.java`.

| Requirement | Classification | Evidence |
|---|---|---|
| Internal UUID primary key | VERIFIED | SQL `id UUID PRIMARY KEY`; entity UUID with generated UUID strategy |
| Public webhook ID | VERIFIED | Non-null `VARCHAR(64)` with uniqueness; entity matching length/uniqueness |
| Workflow association | VERIFIED | Non-null UUID foreign key; entity lazy, required `ManyToOne` |
| Original node ID | VERIFIED | Non-null `VARCHAR(255)`; entity default String length and service limit 255 |
| HTTP method | VERIFIED | Non-null `VARCHAR(10)`; SQL allowed-method check and service validation |
| Authentication mode | VERIFIED | Non-null `VARCHAR(20)`; string enum plus SQL `NONE`/`SECRET_TOKEN` check |
| Nullable digest | VERIFIED | Nullable `VARCHAR(64)`; no plaintext-secret column |
| Enabled state | VERIFIED | Non-null boolean, database default false |
| Timestamps | VERIFIED | Non-null timezone-aware timestamps; entity `Instant`, pre-persist/pre-update callbacks |
| Unique public lookup | VERIFIED | SQL UNIQUE creates PostgreSQL B-tree unique index |
| Unique workflow/node | VERIFIED | `uq_webhook_registration_workflow_node` |
| Workflow deletion behavior | VERIFIED | Foreign key `ON DELETE CASCADE`, plus explicit service deletion |
| Workflow lookup index | VERIFIED | `idx_webhook_registrations_workflow`; composite unique index also starts with workflow ID |

Timestamp conventions match the timezone-aware execution tables in V2. V1 workflow timestamps use the older timestamp-without-time-zone convention; V4 does not alter those columns.

V1, V2, V3, and V4 have no current working-tree changes. Comparison with the pre-registration commit `970f111` shows no V1–V3 changes in `433db95`. No migration was created or edited during Stage 2B or this audit.

### 4.2 Actual local application status — VERIFIED

Inspection target: existing `flowforge-postgres` container, database `flowforge`. Queries used `psql -X`, `ON_ERROR_STOP`, and explicit `BEGIN READ ONLY` / `COMMIT`. Only migration history, schema metadata, index definitions, and aggregate consistency counts were selected. No registration identifiers, definitions, credentials, or digest values were returned.

| Version | Description | Success |
|---|---|---|
| 1 | create workflows table | true |
| 2 | create execution tables | true |
| 3 | execution reliability | true |
| 4 | create webhook registrations | true |

The live table has the expected ten columns, types, lengths, nullability, primary key, public-ID unique key, workflow/node unique key, foreign key with deletion cascade, allowed-method/authentication checks, and workflow index.

Aggregate query result: 0 registrations; 0 enabled registrations contradicting workflow-active/required-secret conditions; 0 registrations missing a corresponding webhook node. The zero invalid counts are **vacuous**, not proof of lifecycle correctness.

### 4.3 Hibernate compatibility — VERIFIED WITH LIMITATIONS

Source mappings and live PostgreSQL definitions are structurally compatible. Production configuration uses PostgreSQL and Hibernate `ddl-auto: validate`. The preceding Stage 2B implementation run reported successful disposable-PostgreSQL registration tests with Flyway and Hibernate validation; that is historical supporting evidence, not a new migration run in this audit.

This audit did **not** start the production application or rerun Hibernate validation against the existing database: normal startup enables Flyway and performs registration/recovery mutations. Fresh H2 tests use Hibernate `create-drop`, not the SQL migration, so they do not independently validate PostgreSQL SQL or database cascade semantics.

Flyway checksum revalidation was not executed. Matching history/schema and unchanged migration files do not constitute a fresh Flyway validation run.

## 5. Registration Lifecycle Audit

### 5.1 Workflow transactions and normal reconciliation

`WorkflowService.createWorkflow()` sanitizes the definition before `saveAndFlush`, then invokes the proxied `WebhookRegistrationService.synchronizeWorkflow()` inside the same REQUIRED transaction. Update locks the parent workflow, applies supplied fields, sanitizes before flushing, and synchronizes. Delete locks the parent, deletes registrations, then deletes the workflow in the same transaction.

| Requirement | Classification | Evidence / limit |
|---|---|---|
| Creation linked to workflow and node | VERIFIED | Constructor stores workflow association and parsed node ID; `createsOpaqueRegistrationWithSafeTimestampsAndNoSecret` passed |
| Backend-owned public ID | VERIFIED | New registration uses `generateWebhookId()`; client configuration cannot set entity identity |
| Stable ID on ordinary save | VERIFIED | Lookup by workflow/node; `preservesIdAndHashAndUpdatesMethodOnSave` passed |
| Stable digest on ordinary save | VERIFIED | Existing entity retained; same test compares stored digest internally without reporting it |
| Method changes preserve identity | VERIFIED | Registration `update()` changes method; integration test passed |
| Unrelated edits do not rotate | VERIFIED WITH LIMITATIONS | No generator call on existing-registration path; ordinary-save coverage exists, but no separate description-only test |
| Inactive workflow disables | VERIFIED | Active-only update test passed |
| Inactive node disables | VERIFIED | `configuration.active` is a real boolean in the serialized model; inactive rotation test passed |
| Secret-required/no-secret remains disabled | VERIFIED | Enable formula requires `hasSecret()` for `SECRET_TOKEN`; creation and auth-mode-transition tests passed |
| Reactivation requires configuration | VERIFIED WITH LIMITATIONS | Same enable formula on synchronization; tested reactivation with a secret and transition back to secret authentication without one; no dedicated no-secret deactivate/reactivate sequence |
| Node removal deletes registration | VERIFIED | Integration test passed |
| Workflow deletion deletes registrations | VERIFIED | Integration test passed; live PostgreSQL FK cascade inspected |
| Multiple webhook nodes reconcile independently | VERIFIED WITH LIMITATIONS | Additional repository-mock probe verified two independent IDs/states, stable repeated saves, and deletion of only the removed node; no multi-node database integration test |
| Duplicate/malformed webhook configuration rejected | VERIFIED | Duplicate IDs (including overlap with another node type) and ID/type/method/authentication/active validation in parser; existing duplicate and invalid-type integration test passed |
| Failure rolls back workflow and registration | VERIFIED | Create and update tests deliberately flush real changes and then throw; both rollback tests passed |
| Sanitization before flush | VERIFIED | Create line 43 precedes line 45; update line 79 precedes line 80; no intervening repository call after assigning unsanitized update JSON |

Changing authentication to `NONE` deliberately clears the stored digest. Returning to `SECRET_TOKEN` requires explicit generation. This is documented behavior, not ordinary-save hash loss.

### 5.2 Definition parsing and execution boundary

The actual fields are node `id`, `type`, and `configuration`; webhook configuration supports `method`, `authentication`, boolean `active`, and retained `responseMode`. Missing method/authentication/active receive documented defaults. Explicit wrong types and unsupported values are rejected. Definitions must be objects; a present `nodes` field must be an array. Duplicate IDs involving a webhook node are rejected.

Registration parsing deliberately does not certify graph reachability, edges, executor compatibility, or a single trigger. Multiple webhook registrations can be persisted at this stage even though the execution graph currently supports one root trigger. This separation is **VERIFIED WITH LIMITATIONS** and documented; it is not evidence that those workflows can execute.

### 5.3 Locking, propagation, and lazy loading

Ordinary update/delete and rotation use `WorkflowRepository.findByIdForRegistrationUpdate()` with `PESSIMISTIC_WRITE`. Rotation reads its registration after obtaining that parent lock, so supported management operations use a consistent lock order. Metadata mapping uses scalar fields inside read-only transactions. Rotation accesses its lazy workflow inside its transaction. Inbound lookup uses an entity graph for the workflow. No normal management-path lazy-loading defect was found by inspection or tests.

**F01 — FAILED:** `WebhookRegistrationStartupSync.synchronizeSavedWorkflows()` uses `workflowRepository.findAll().forEach(registrationService::synchronizeWorkflow)` under one transaction, without the parent lock. `synchronizeWorkflow()` itself does not acquire it. A database-free probe confirmed that startup reconciliation never invokes the locking repository method.

An allowed interleaving is: startup loads an active node; another transaction locks the workflow, disables that node, synchronizes and commits; startup later reads the registration using its stale workflow definition and writes enabled=true. Workflow activity can remain true while the actual saved node is inactive. The normal inbound resolver checks registration enabled and workflow active, not the current node flag. This violates authoritative lifecycle consistency. A real concurrent database reproduction is **NOT VERIFIED**, but the missing locking path and stale-state possibility are supported directly by code. The risk is clearest during multi-instance startup or startup while management traffic remains possible. A readiness-aware deployment that fully excludes concurrent mutations can reduce the single-instance window, but that exclusion is not enforced by this service.

Startup also processes all workflows in one transaction: one malformed legacy definition can fail the startup listener and roll back the entire reconciliation batch. Existing historical data compatibility and multi-instance startup contention are **NOT VERIFIED**. No application startup was performed during this audit.

## 6. Public ID and Secret Security Audit

| Requirement | Classification | Evidence |
|---|---|---|
| Secure public-ID randomness | VERIFIED | `WebhookRegistrationService.generateWebhookId()`: 32-byte buffer filled by `SecureRandom` |
| URL-safe encoding without padding | VERIFIED | `Base64.getUrlEncoder().withoutPadding()`; integration test checks 43-character shape |
| Database public-ID uniqueness | VERIFIED | Migration and actual PostgreSQL unique index |
| Stable backend identity | VERIFIED | Composite workflow/node lookup retains existing registration |
| Secret entropy | VERIFIED | Rotation uses an independent 32-byte SecureRandom buffer: 256 bits before encoding |
| Hash-only persistence | VERIFIED | SHA-256 of UTF-8 secret; hexadecimal encoding stored in the digest column |
| Plaintext returned only explicitly | VERIFIED | Generated plaintext is returned from rotation service to `WebhookSecretResponse`; ordinary metadata DTO has no secret/digest field |
| No generated plaintext in workflow JSON | VERIFIED | Generated secret is not assigned to the definition; sanitizer removes untrusted configuration properties before persistence; integration tests passed |
| Rotation replaces old digest | VERIFIED | Rotation test confirms digest change and correct digest of the replacement internally |
| Rotation transactional | VERIFIED | Service transaction, parent lock, managed entity update, flush before metadata response |
| Rotation honors node/workflow inactivity | VERIFIED | Disabled-node and inactive-workflow rotation test passed |
| Constant-time digest comparison exists | VERIFIED | Existing `authenticate()` compares equal-length SHA-256 hex bytes with `MessageDigest.isEqual` |
| Formal inbound authentication acceptance | NOT VERIFIED | Existing earlier-stage controller/helper are present and tested in the legacy suite; no Stage 3 acceptance is granted here |
| Public-ID collision recovery | VERIFIED WITH LIMITATIONS | Uniqueness prevents duplicate publication and transaction rolls back; no bounded collision retry, no forced-collision test. Collision probability is negligible with 256-bit random IDs |

The normal post-rotation lookup uses the replacement digest, so the previous secret no longer matches current stored state. The pre-existing inbound request path can retain a stale registration object across rotation; section 13 describes why this is not a blanket guarantee for already in-flight requests.

Sensitive-data inspection found no logging calls in the registration service, workflow service, management controller, or webhook response DTOs. No generated webhook secret or digest is returned through ordinary metadata. The secret response is marked `Cache-Control: no-store`.

Limits of this conclusion: it covers application-owned generated secrets and supported webhook configuration fields, not arbitrary credential text an administrator deliberately puts into a workflow name, body, or other user data. Workflow definitions and execution inputs are user data, not a general-purpose credential vault. Existing HTTP node credentials and browser draft persistence need their own policies. The entity has a digest getter, but the management controller serializes explicit safe DTOs rather than the entity. No raw registration entity serialization was found in the inspected controllers.

The shared `ApiExceptionHandler.handleBadRequest()` returns exception messages for JSON binding errors. Such messages can include submitted field content. This is a pre-existing general error-redaction concern, not a demonstrated disclosure of a server-generated webhook secret through the rotation endpoint. No sensitive payload was sent to test this possibility.

## 7. Management API Audit

### 7.1 Actual contract

| Route | Success | Resource / configuration failures |
|---|---|---|
| `GET /api/workflows/{workflowId}/webhooks` | 200, metadata array; empty for a workflow with no registrations | 404 for unknown workflow |
| `GET /api/workflows/{workflowId}/webhooks/{nodeId}` | 200, one metadata object | 404 for missing composite workflow/node registration |
| `POST /api/workflows/{workflowId}/webhooks/{nodeId}/secret` | 200, one newly generated plaintext value plus `registration` metadata; no-store | 404 for unknown workflow/registration; 400 for `NONE` authentication |

No separate create-registration API exists. Workflow reconciliation provisions registrations. Each explicit POST to the secret route creates or rotates; retrying that POST is not idempotent and invalidates the previous result. A lost response requires another explicit rotation rather than retrieval of the previous plaintext.

Metadata fields: `nodeId`, `webhookId`, `httpMethod`, `authenticationMode`, `enabled`, `secretConfigured`, `createdAt`, `updatedAt`. No internal registration UUID, digest, administrator token, or security configuration is exposed.

### 7.2 Authentication and resource scoping

**VERIFIED:** management routes remain under `/api/workflows/...`, outside the public inbound exception. The filter protects `/api` and `/api/**`, normalizes path handling for API detection, and permits only exact POST verification/inbound route shapes plus OPTIONS. Nested webhook management paths are protected. Authentication accepts exactly one syntactically valid bearer header; it does not read query, cookie, or body credentials. Missing/invalid credentials return 401; missing configured administrator token returns 503. Verification uses the same service and returns 204 on success.

Fresh results: all 31 `AdminApiAuthenticationTest` invocations passed. The registration integration test explicitly verifies absent credentials on all three management routes. Incorrect-token and malformed-header behavior are verified at the shared filter/service level, not separately repeated for every route.

**VERIFIED WITH LIMITATIONS:** lookup is scoped by both workflow ID and node ID. A node from a different workflow or a non-webhook node normally has no matching registration and returns 404. List checks workflow existence directly; rotation locks/checks the workflow before querying the registration; single GET uses the composite registration query and FK integrity instead of a separate workflow-existence query. The table is authoritative, and node type is not reparsed on each management GET/rotation. Dedicated cross-workflow and non-webhook-node negative integration tests are missing. Shared administrator access intentionally permits managing all workflows; there is no user ownership boundary.

### 7.3 Reproduced HTTP defects — FAILED

A temporary database-free MockMvc probe installed the actual `WebhookManagementController` and `ApiExceptionHandler`, with a mocked registration service. It omitted the auth filter to isolate controller/advice behavior after authentication; it did not call a live application or database.

| Probe | Expected | Observed |
|---|---|---|
| GET metadata list with a malformed workflow UUID | 400 | **500** |
| POST to the metadata-list route, whose controller supports GET | 405 | **500** |

Root cause: `ApiExceptionHandler.handleUnexpected(Exception, ...)` catches `MethodArgumentTypeMismatchException` and `HttpRequestMethodNotSupportedException`, returning a generic 500. This does not leak the supplied value in these observed responses, but breaks status semantics and mislabels client mistakes as server faults. Existing automated tests do not exercise these cases.

Known business errors use the existing ErrorResponse structure. Filter 401/503 responses and verification errors use smaller payloads; verification 401 is empty. This is safe but not one uniform error schema.

**F04 — VERIFIED WITH LIMITATIONS:** registration validation accepts nonblank node IDs of up to 255 characters, including a slash, as confirmed by a database-free sanitizer probe. Management routes require one path segment; production servlet defaults can reject encoded slashes or route them differently. The frontend's generated node IDs use safe characters, so normal editor-created nodes avoid this case. Actual deployed slash-node management behavior was not tested.

## 8. Workflow Activation and Frontend Audit

No frontend file was changed during Stage 2B or this audit.

| Requirement | Classification | Finding |
|---|---|---|
| Activation preserves saved graph | VERIFIED | `workflows-page.ts:605–615` sends only `{ active }`; it does not call `buildWorkflowDefinition()` |
| Backend preserves omitted definition | VERIFIED | `WorkflowService.updateWorkflow()` changes definition only when supplied; `activationOnlyUpdatePreservesDefinitionAndStableRegistration` passed |
| Editor save does not force active | VERIFIED | `workflow-editor.ts:1553` save payload contains name/description/definition, not active |
| Editor loads workflow activity | VERIFIED | `loadWorkflowFromApi()` sets `workflowActive` from backend response |
| Nodes/connections retained for editing | VERIFIED WITH LIMITATIONS | `getWorkflow()` returns raw full response; editor reads definition arrays. Browser save/reload was not manually exercised |
| Registration identity backend-owned | VERIFIED | ID comes from management metadata, and backend strips client webhook identity fields from configuration |
| Generated plaintext not stored in draft JSON | VERIFIED WITH LIMITATIONS | Secret reveal is a separate component signal; it is not inserted into `state.nodes`. Draft workflow state itself is persisted in localStorage |
| Metadata shape consumption | VERIFIED WITH LIMITATIONS | API metadata responses pass through at runtime; frontend interface omits new timestamps, and rotation type exposes only secret. UI refetches registration list after rotation |

`WorkflowApiService.toWorkflow()` intentionally discards definitions when mapping list/create/update responses to the dashboard model. The editor retrieves the full definition through `getWorkflow()`, and activation sends a partial update, so this mapping no longer causes the reported activation graph-loss defect.

Pre-existing limitations outside the Stage 2B delta:

- `duplicateWorkflow()` still calls `buildWorkflowDefinition()` and duplicates an empty graph rather than the original graph. This is a separate existing CRUD defect, not the activation bug.
- A new unsaved editor workflow saves a local draft rather than provisioning a backend workflow. Its registration cannot be provisioned until a backend workflow exists.
- `webhookUrl()` constructs a relative path with the default `/api` base. That is not an externally usable full webhook URL.
- Webhook configuration UI already exists in `433db95`; it was not implemented or accepted as Stage 4 in this audit. Stage 4 must validate the complete UX, metadata types, public URL, save/error states, and transient secret handling.
- Legacy/untrusted properties already present in local workflow drafts are not independently sanitized by the frontend persistence function. Normal generated-secret flow is separate, but do not treat arbitrary draft storage as a safe credential store.

## 9. Existing Functionality Regression Audit

The Stage 2B working diff contains no execution runtime, executor, graph-validator, execution-service, or Angular changes. Workflow save sanitization and parent-row locking are the main new points of interaction with existing behavior.

| Area | Fresh automated evidence | Classification and limit |
|---|---|---|
| Workflow create/read/update/delete | `WorkflowCrudIntegrationTest`; registration creation/update/delete tests | VERIFIED WITH LIMITATIONS: passing H2 integration coverage; no manual browser lifecycle or live PostgreSQL writes |
| Active/inactive state | Registration active-only update and disabled-node/rotation tests | VERIFIED: ordinary sequential path; startup race excluded from this claim |
| Manual execution, snapshots, history, node records, logs | `ExecutionApiIntegrationTest.executesSnapshotAndExposesExecutionHistoryNodesAndChronologicalLogs` and related cases | VERIFIED WITH LIMITATIONS: automated H2 evidence; no deployed manual run |
| Async dispatch | `ExecutionDispatchIntegrationTest.dispatchesAfterCommitTransitionsThroughRunningAndReachesTerminalState`; worker failure test | VERIFIED WITH LIMITATIONS: after-commit path and worker tests passed |
| Graph validation/traversal/data passing | Four validator tests plus connected-node/fan-out API tests | VERIFIED WITH LIMITATIONS: covered graph shapes passed; not exhaustive arbitrary graphs |
| Set Data / Log Message | Connected-node, snapshot, data-passing and log/history integration cases | VERIFIED WITH LIMITATIONS: exercised through existing workflows |
| IF/ELSE, branch skipping and rejoining | Conditional selection/fan-out/rejoin, nested conditionals, invalid evaluation, and legacy-handle API tests; evaluator tests | VERIFIED WITH LIMITATIONS: all covered cases passed |
| Cancellation and timeout | Queued cancellation API test; active cancellation and timeout dispatch integration tests | VERIFIED WITH LIMITATIONS: automated cooperative behaviors passed; external effects are not rolled back |
| Retry and recovery | Immutable retry API test, interrupted-node recovery, queued redispatch, recovery-service mock test | VERIFIED WITH LIMITATIONS: covered restart/retry state paths passed; not real process-crash recovery |
| Execution state transitions | `ExecutionStatusTest` | VERIFIED: tested transition cases passed |
| HTTP Request node | Sixteen `HttpRequestExecutorTest` cases plus `OutboundAddressPolicyTest` | VERIFIED WITH LIMITATIONS: deterministic local-server tests cover methods, parsing, errors, cancellation, timeout, size limits, SSRF/DNS/redirect/header protections; no external production network test |

No Stage 2B regression was observed in these executed cases. That conclusion is bounded by coverage; unchanged files and successful compilation alone are not the evidence used.

The suite also runs six earlier `WebhookIntegrationTest` cases, including inbound-to-execution paths. Their passing result confirms that earlier code is reachable in the test application; it does not constitute formal Stage 3 acceptance or prove its concurrency/security completeness.

## 10. Backend Test Results

### 10.1 Commands and isolation

Executed from `backend/`:

```sh
./gradlew test --rerun-tasks
./gradlew build
```

For both invocations, process environment overrides explicitly selected the existing in-memory H2 test URL/driver, H2 dialect, test database user with empty password, Hibernate `create-drop`, and `SPRING_FLYWAY_ENABLED=false`. No production administrator token was supplied or printed. Integration classes use the existing test-only administrator configuration.

The forced test rerun is significant: results are fresh, not merely old XML or an up-to-date task. The build then reused those passing test outputs and generated no source changes. It completed successfully with tasks up-to-date.

Initial sandbox execution could not access the Gradle wrapper lock; the retry with approved cache access succeeded. Compilation emitted a pre-existing deprecated-API note for `HttpRequestExecutor`; no compilation failure occurred.

### 10.2 Fresh totals

| Test class | Tests | Passed | Failed/errors | Skipped |
|---|---:|---:|---:|---:|
| `ExecutionApiIntegrationTest` | 14 | 14 | 0 | 0 |
| `ExecutionDispatchIntegrationTest` | 3 | 3 | 0 | 0 |
| `WebhookIntegrationTest` | 6 | 6 | 0 | 0 |
| `WebhookRegistrationIntegrationTest` | 13 | 13 | 0 | 0 |
| `WorkflowCrudIntegrationTest` | 2 | 2 | 0 | 0 |
| `ConditionEvaluatorTest` | 3 | 3 | 0 | 0 |
| `HttpRequestExecutorTest` | 16 | 16 | 0 | 0 |
| `OutboundAddressPolicyTest` | 1 | 1 | 0 | 0 |
| `WorkflowExecutionWorkerTest` | 1 | 1 | 0 | 0 |
| `AdminApiAuthenticationTest` | 31 | 31 | 0 | 0 |
| `ExecutionRecoveryServiceTest` | 1 | 1 | 0 | 0 |
| `ExecutionStatusTest` | 2 | 2 | 0 | 0 |
| `WebhookRateLimiterTest` | 1 | 1 | 0 | 0 |
| `WorkflowGraphValidatorTest` | 4 | 4 | 0 | 0 |
| **Total** | **98** | **98** | **0** | **0** |

Results were read from newly generated `backend/build/test-results/test/TEST-*.xml`, dated 2026-11-05 around 15:06 UTC. Backend build: **VERIFIED — BUILD SUCCESSFUL**. `git diff --check`: passed.

### 10.3 Additional audit probes

A temporary Java source-launcher helper outside the repository used the compiled application classes, MockMvc, and Mockito repositories, with no application startup or database connection. No new committed or untracked application test was added.

Observed:

- malformed workflow UUID: 500;
- unsupported management HTTP method: 500;
- two-node reconciliation independent: true;
- repeated two-node public IDs stable: true;
- removed-node-only deletion: true;
- startup parent-workflow lock acquired: false;
- slash-containing node ID accepted: true.

The first sandbox probe attempt failed because Mockito's agent could not attach. Its approved retry outside the sandbox succeeded; this was an audit-tool environment issue, not a product test failure. These probes add observations but are not counted in the 98-test suite.

Missing coverage: concurrent save/rotation/startup on PostgreSQL, explicit cross-workflow/non-webhook-node negatives, timestamp monotonicity, lost rotation response, forced public-ID collision, all malformed configuration shapes, and browser/end-to-end acceptance. No tests were weakened or fixed.

## 11. Frontend Test Results

Stage 2B changed no frontend files, so frontend execution was not mandatory under the conditional audit requirement. Additional frontend tests and build were nevertheless run for regression evidence using installed Node 22.22.0:

```sh
PATH=/Users/bickyrajkayastha/.nvm/versions/node/v22.22.0/bin:$PATH npm test -- --watch=false
PATH=/Users/bickyrajkayastha/.nvm/versions/node/v22.22.0/bin:$PATH npm run build
```

The selected Node installation avoids the default Node version's incompatibility with Angular CLI.

| Check | Result |
|---|---|
| Frontend unit tests | **VERIFIED: 31 passed, 0 failed, 9 test files passed** |
| Angular production build | **VERIFIED: successful**; output under `dist/arbeitfluss` |
| Browser/manual end-to-end verification | **NOT VERIFIED**; not performed |

The unit tests include administrator auth service/interceptor, workflow API service, workflow editor, pre-existing webhook UI, execution API/details/page, dashboard, and app coverage. No backend service or live database was used. These tests do not substitute for a browser-to-backend acceptance test.

## 12. Documentation Audit

Inspected `docs/runtime/WEBHOOK_TRIGGER.md` and `docs/runtime/EXECUTION_ENGINE.md` without modifying them.

| Topic | Classification | Finding |
|---|---|---|
| Registration versus execution distinction | VERIFIED WITH LIMITATIONS | Clearly identifies Stage 2B delivery and earlier unaccepted ingress/runtime code; documentation is not a runtime feature gate |
| Stable public IDs and lifecycle | VERIFIED | Describes workflow/node pairing, creation, deletion, deactivation and reactivation |
| Secret generation/storage/rotation | VERIFIED | Describes random bytes, hash-only storage, explicit one-time response, no-store and mode transitions |
| Effective enabled state | VERIFIED WITH LIMITATIONS | Formula matches normal synchronization; startup concurrency caveat is not documented |
| Management API contract | VERIFIED WITH LIMITATIONS | Routes/metadata/statuses for normal business errors match; malformed UUID/wrong-method 500 defects are not documented |
| Migration | VERIFIED | V4 and its pre-existing committed status are accurately described; actual local application was confirmed read-only |
| Shared administrator limitations | VERIFIED | Shared identity, no users/roles/individual revocation/per-user attribution |
| No exactly-once guarantee | VERIFIED | Explicitly stated in webhook and execution documentation |
| Single-instance architecture | VERIFIED WITH LIMITATIONS | Execution recovery documents single-instance behavior; webhook startup locking/multi-instance limitations need a stronger statement |
| Remaining Stage 3 work | VERIFIED | Explicitly requires review/validation of existing inbound/runtime integration |
| Remaining Stage 4 work | VERIFIED WITH LIMITATIONS | Refers to later frontend/public-URL work, but does not explicitly enumerate Stage 4 acceptance despite pre-existing UI |

The documentation's prior disposable-PostgreSQL verification statement is historical evidence from Stage 2B implementation. This audit did not repeat migrations. Do not interpret its acceptance wording as proof that pre-existing later-stage beans are disabled.

## 13. Security and Architecture Risks

| ID | Severity / classification | Exact evidence and consequence |
|---|---|---|
| F01 | High — FAILED | `WebhookRegistrationStartupSync.java:22–25` loads workflow snapshots and calls `synchronizeWorkflow()` without the lock used in normal mutations. A stale snapshot can re-enable a disabled node or overwrite newer method/authentication state. Probe confirms lock omission; concurrent database reproduction not performed |
| F02 | High — VERIFIED WITH LIMITATIONS; blocks treating this as a registration-only deployment | `WebhookController.java:61–95` is mapped without a feature flag and calls execution creation. `AdminApiAuthenticationFilter.java:31–33` exempts the exact public POST shape. Registration enablement can therefore expose earlier unaccepted ingress code; documentation alone does not separate deployment stages |
| F02a | High, pre-existing Stage 3 concern — VERIFIED WITH LIMITATIONS | `WebhookController.receive()` resolves/authenticates in one service transaction, then calls `ExecutionService.startWebhookExecution()` with only workflow ID. The latter checks workflow active and graph trigger type, not current registration/node ID, secret digest, node activity or request method. Rotation/node replacement/disable between these steps can authorize against stale state. This audit does not perform a live race or accept the inbound implementation |
| F03 | Medium — FAILED | Shared `ApiExceptionHandler.java:54–56` converts malformed UUID and unsupported method exceptions to 500; both reproduced without a database |
| F04 | Low — VERIFIED WITH LIMITATIONS | `parseWebhookNodes()` validates length/type but not URL-safe node-ID characters; slash acceptance reproduced. Such IDs can be difficult or impossible to manage through the current single-segment route under default servlet decoding |
| F05 | Medium verification gap — NOT VERIFIED for concurrent behavior | Existing tests are sequential and predominantly H2. Normal locking is sound by inspection, but PostgreSQL concurrency, startup contention, and several resource-ownership negatives lack dedicated tests |
| F06 | Availability concern — VERIFIED WITH LIMITATIONS | Startup reconciles all saved workflows in one transaction and strict parsing can fail on a legacy malformed definition. No per-workflow isolation or legacy-data audit was demonstrated |
| F07 | Low documentation/operational gap — VERIFIED WITH LIMITATIONS | Secret POST always rotates and is not idempotent; last successful rotation wins. Concurrent rotations serialize but can return a value already superseded by another explicit rotation. This is expected shared-admin behavior, not a proven transaction defect |

Public-ID uniqueness protects integrity even on an astronomically unlikely random collision; lack of collision retry is an availability edge case, not a predictable-ID vulnerability. Database constraints do not encode every derived invariant, such as enabled requiring a digest for Secret Token mode; service transactions own those rules. No normal management API bypass, plaintext-secret column, or metadata digest disclosure was found.

Normal locked workflow saves serialize writes, but there is no optimistic client revision check: a later full-definition save can replace an earlier user's graph edit. This is ordinary last-save-wins behavior and should not be confused with the distinct startup race that bypasses locking.

No production-readiness claim is made for pre-existing single-instance rate limiting, process recovery, or webhook input parsing. They are outside Stage 2B acceptance, and no new mechanism was introduced in this audit.

## 14. Unresolved Issues

1. F01 remains unfixed: startup reconciliation does not participate in the registration mutation lock protocol.
2. The deployment boundary remains unresolved: earlier inbound controller/runtime/UI code is present and registered even though Stage 3/4 acceptance is pending.
3. F03 remains unfixed: malformed workflow UUIDs and wrong HTTP methods are reported as server errors.
4. Node IDs accepted for registration are not constrained to the management API path contract.
5. PostgreSQL concurrency and startup races have not been empirically tested. No such test was added because this audit cannot modify application tests or existing database state.
6. Live PostgreSQL schema is present but contains no registrations. No live registration lifecycle or manual browser workflow was exercised.
7. Fresh live Hibernate/Flyway validation was not run because ordinary startup can mutate schema/data. Source-to-schema mapping was inspected instead.
8. Frontend clone behavior, draft persistence boundaries, metadata typing and complete public URL handling remain Stage 4/existing-CRUD concerns, not fixes performed here.
9. Error redaction of general JSON-binding exceptions and runtime behavior of slash-containing node IDs remain unverified beyond source inspection and the accepted-ID probe.

## 15. Recommended Remediation Steps

These are recommendations only; no fixes were applied.

1. Make every registration reconciler, including startup, acquire and reread the parent workflow through one consistent transactional locking protocol. Add deterministic PostgreSQL concurrency tests for node deactivation versus startup, save versus rotation, and concurrent rotations. Verify stable ID/digest preservation after the interleavings.
2. Decide explicitly how earlier Stage 3-scoped beans are handled before deploying Stage 2B. A registration-only acceptance label must not be mistaken for an execution-disabled application. Preserve earlier work while making the deployment/acceptance boundary explicit.
3. Correct management HTTP error translation for malformed path parameters and unsupported methods, with targeted tests proving 400 and 405 while preserving 401/503 authentication behavior. Review generic binding-error message redaction separately.
4. Align node-ID validation and path addressing, or document and test the allowed character set. Add non-webhook-node and cross-workflow lookup/rotation tests.
5. Document startup/legacy-data compatibility and shared-admin rotation semantics. Clarify remaining Stage 4 work and distinguish actual registered code from formally accepted functionality.
6. After separately approved remediation, rerun backend regression tests/build and registration tests on disposable PostgreSQL. Do not run migration or write tests against the user's existing database.

Manual verification still needed, in an explicitly authorized test environment:

- Create a workflow, generate a secret, save unrelated edits and method changes, deactivate/reactivate workflow and node, and confirm identity/state persistence across restart.
- Remove and re-add a trigger, delete a workflow with execution history, and verify registration removal/cascade without exposing secret material.
- Use separate administrator clients to verify rotation and concurrent mutation behavior after the locking fix.
- Confirm missing/wrong credentials on all management routes, resource ownership negatives, and corrected 400/405 behavior.
- Validate browser activation/save/reload, empty-draft provisioning, absolute public URL display, and absence of generated secrets from persistent browser workflow state.
- Treat inbound request parsing, authentication-to-execution consistency, dispatch and limiter acceptance as Stage 3 work; do not infer it from this audit's build or legacy test results.

## 16. Final Stage 2B Assessment

**Registration persistence, normal lifecycle synchronization, backend-owned identity, hash-only secret storage, metadata safety and administrator protection are VERIFIED or VERIFIED WITH LIMITATIONS with passing automated evidence.** The local V4 schema is applied and structurally consistent. Existing tested manual execution and runtime behavior show no Stage 2B regression.

**The essential lifecycle guarantee is not complete across all actual entry points:** startup synchronization bypasses locking and can restore stale enabled/configuration state. The existing ingress path makes that inconsistency materially relevant to security, rather than a metadata-only discrepancy. API validation/status defects and missing concurrency coverage add to the remediation needs.

This report does not approve production use, does not accept earlier inbound code as Stage 3 complete, and does not authorize or start implementation. Resolve the lifecycle lock gap and deployment boundary, then repeat focused verification before advancing. All manual verification listed in section 15 remains outstanding.

DO NOT APPROVE STAGE 3

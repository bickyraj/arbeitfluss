# Milestone 9.6 — Webhook Trigger Architecture Inspection

**Status:** Stage 1 complete; implementation is proceeding after the user approved adding a minimal API authentication layer.

## Approved security decision

The user approved adding a minimal authentication/authorization layer before webhook management APIs. Because this application has no user/account model, the smallest compatible approach is a single deployment-configured administrator bearer token (`FLOWFORGE_ADMIN_TOKEN`) required for all management `/api/**` requests. The public inbound `/api/webhooks/{webhookId}` endpoint and token-verification endpoint are the only intended unauthenticated API routes. The token must be configured outside source control, have sufficient entropy, and be compared in constant time. The Angular app will request the token at login, keep it in memory only, and attach it through an HTTP interceptor; refresh requires signing in again. This is shared-admin access, not multi-user identity or role-based authorization.

## Relevant files

### Backend

- `backend/src/main/java/com/flowforge/executor/ManualTriggerExecutor.java` — current trigger executor; returns a deep copy of its input.
- `backend/src/main/java/com/flowforge/executor/NodeExecutor.java` and `NodeExecutorRegistry.java` — executor contract and Spring component registry keyed by node type.
- `backend/src/main/java/com/flowforge/validation/WorkflowGraphValidator.java` — validates nodes and currently requires exactly one `manual-trigger`.
- `backend/src/main/java/com/flowforge/runtime/WorkflowExecutionRuntime.java` — executes the validated graph, passing the execution snapshot's trigger input to the root trigger.
- `backend/src/main/java/com/flowforge/runtime/ExecutionDispatcher.java`, `WorkflowExecutionWorker.java`, and `ExecutionQueuedEvent` — existing asynchronous dispatch and worker path.
- `backend/src/main/java/com/flowforge/service/ExecutionService.java` — validates, snapshots, persists, and dispatches executions; currently fixes the trigger type to `MANUAL`.
- `backend/src/main/java/com/flowforge/service/ExecutionStateService.java` — claims queued work and manages execution/node states.
- `backend/src/main/java/com/flowforge/entity/Workflow.java` and `Execution.java` — workflow active state and immutable execution snapshot/trigger input persistence.
- `backend/src/main/java/com/flowforge/service/WorkflowService.java`, `controller/WorkflowController.java`, and workflow DTOs — transactional workflow create/update/delete and REST responses.
- `backend/src/main/java/com/flowforge/controller/ExecutionController.java` and execution DTOs — manual execution and existing history/details API.
- `backend/src/main/resources/db/migration/V1__create_workflows_table.sql`, `V2__create_execution_tables.sql`, and `V3__execution_reliability.sql` — Flyway/PostgreSQL schema history.
- `backend/src/main/resources/application.yml` and `backend/build.gradle.kts` — deployment configuration and dependencies.

### Frontend

- `src/app/core/models/workflow-editor.model.ts` — `webhook-trigger` is already in the frontend node-type union.
- `src/app/features/workflows/workflow-editor/workflow-editor.ts` — node library contains Webhook Trigger, but its default configuration uses a placeholder URL; there is no webhook-specific configuration panel yet. The editor save payload currently sets `active: true`.
- `src/app/core/services/workflow-api.service.ts` — workflow API contracts and calls; the workflow response definition is optional and is discarded when mapped to the dashboard model.
- `src/app/features/workflows/workflows-page.ts` — activation toggles through workflow update; `buildWorkflowDefinition()` returns an empty graph, so that path currently sends an empty definition while changing active state.
- `src/app/core/models/execution.model.ts`, `src/app/features/executions/executions-page.ts`, and `src/app/features/executions/execution-details.ts` — execution trigger type is displayed in history; details show per-node input/output and logs but do not prominently show the trigger type.
- `docs/runtime/EXECUTION_ENGINE.md` — current execution architecture and API documentation; should be updated when webhook support is implemented.

## Existing architecture

The backend registers each `NodeExecutor` as a Spring component; `NodeExecutorRegistry` builds an immutable type-to-executor map and rejects duplicate registrations. The frontend recognizes `webhook-trigger`, but the backend has no executor for it. `WorkflowGraphValidator` currently permits exactly one root trigger and recognizes only `manual-trigger`.

The manual execution API resolves the workflow by its internal UUID, copies the supplied trigger input, validates its serialized size, takes an immutable workflow snapshot, validates the graph, writes an execution plus one node-execution row per graph node, and registers an after-commit callback with the existing dispatcher. The dispatcher publishes queued work to `WorkflowExecutionWorker`, which calls `WorkflowExecutionRuntime`; the runtime passes the persisted trigger input to the root trigger executor. This is the path webhook requests should reuse.

Workflows persist their `active` flag and JSON definition in PostgreSQL. `WorkflowService` wraps create, update, and delete in transactions. There is no separate activation API: `PUT /api/workflows/{id}` can update `active`. Workflow responses include the full definition. Flyway manages schema changes, and Hibernate is configured to validate rather than create schema.

## Trigger and execution flow

1. Manual requests call `POST /api/workflows/{workflowId}/executions`; `ExecutionService.startExecution()` currently sets `ExecutionTriggerType.MANUAL`.
2. The graph validator identifies the single manual trigger as the root. The runtime supplies the execution's trigger input to that node.
3. The manual trigger executor returns that input unchanged, after which normal graph execution proceeds.
4. Execution creation and node tracking are synchronous and transactional; the workflow itself is dispatched only after commit and executed asynchronously.
5. Execution history is workflow-scoped and already includes trigger type. Node execution input/output provides a safe place for the sanitized webhook input, provided authentication secrets and sensitive headers never enter it.

The trigger enum currently contains only `MANUAL`. The `executions.trigger_type` column is a string enum with no database check constraint over trigger-type values; adding `WEBHOOK` requires a Java enum addition, not a schema constraint change. The existing execution input cap is 1 MiB and applies after JSON parsing/serialization.

## Proposed webhook registration model

Use a persistent `webhook_registrations` table. A registration should be keyed to a workflow and trigger node, with a unique public random identifier, configured method, enabled flag, authentication mode, one-way secret hash, and timestamps. Enforce uniqueness for public ID and `(workflow_id, node_id)`; use a foreign key to `workflows` with deletion cascade and indexes for public-ID lookup and workflow association.

Generate public identifiers from at least 32 cryptographically secure random bytes encoded URL-safe without padding. Keep the identifier stable when the same workflow/node is saved. Synchronize registration changes in the same transaction as workflow create/update/delete: create or update registrations from the validated saved definition; preserve public ID and secret hash for an existing workflow/node; disable registrations when the workflow or node is inactive; remove registrations for deleted nodes and workflows. Workflow activation remains authoritative, with the node's `active` option also required for receiving requests.

The registration table, rather than user-authored JSON, is authoritative for endpoint resolution, enabled status, configured method, and authentication verification. The JSON definition may retain non-secret display/configuration settings, but must never store a plaintext secret or a client-supplied public identifier as proof of authorization.

## Security design

- **Current gap and resolution:** No Spring Security configuration or security dependency was found. The user approved a minimal shared-admin-token layer: require an environment-provided administrator bearer token for management `/api/**` endpoints, while keeping only inbound webhooks and token verification public. This avoids adding a large auth dependency or user model, but is shared-admin access rather than multi-user identity/roles.
- **Secret:** Generate at least 32 random bytes and return the plaintext only in the explicit create/regenerate-secret response. Store only a one-way hash. Since generated tokens have high entropy, a SHA-256 digest with `MessageDigest.isEqual` for constant-time digest comparison is a small JDK-only approach; never put the token in workflow JSON, routine GET responses, logs, or execution data. Missing/incorrect secret returns 401. Secret rotation invalidates the prior secret.
- **Management API:** Add backend-owned endpoints to retrieve non-secret registration metadata (public URL, method, and effective state) and explicitly generate/regenerate a secret. The ordinary workflow APIs must not expose hashes or plaintext tokens. The browser can construct the full URL from the API base URL and returned opaque public ID, or consume a server-provided URL if the backend has a configured public base URL.
- **Endpoint resolution:** Accept requests only at `/api/webhooks/{webhookId}`. Resolve registration by the random ID, not a caller-provided workflow ID. Unknown and inactive registrations should consistently return 404. Verify method and authentication before any execution record is created; a method mismatch returns 405 with the configured method in `Allow`.
- **Request parsing:** Read from the servlet input stream with an enforced byte counter (and reject declared oversized `Content-Length` early) rather than loading an unbounded `@RequestBody`. Set a 1 MiB request cap, reject unsupported content types, and bound JSON depth/string sizes. Validate the final wrapped trigger-input size against the existing execution input limit before persisting.
- **Sanitization:** Preserve JSON bodies; represent query values as bounded values/arrays; support bounded text payloads if retained in scope. Include only explicitly allowlisted non-sensitive headers such as `content-type`, `user-agent`, and `x-request-id`. Exclude the FlowForge secret, `Authorization`, cookies, and other credentials. Do not log request bodies or secrets.
- **Rate limiting:** No existing limiter or Redis dependency was found. Use a documented, thread-safe, in-memory per-webhook/source-IP limiter with bounded/expiring state (initial proposal: 60 requests per minute per key; return 429 when exceeded). It is single-instance only, resets on restart, and needs a trusted-proxy policy for client-IP derivation; it is not distributed coordination.
- **Failures:** Map malformed payload to 400, invalid/missing secret to 401, unknown/inactive registration to 404, method mismatch to 405, over-limit body to 413, rate limiting to 429, and internal failures to a generic 500 response without stack traces or persistence details.

## Database changes required

Add a Flyway migration for `webhook_registrations` with an internal UUID primary key, unique public random ID, workflow foreign key, trigger node ID, HTTP method, authentication mode, nullable secret hash, effective enabled flag, and created/updated timestamps. Add unique constraints/indexes for public ID and workflow/node lookup. Delete registrations transactionally with workflows and reconcile them transactionally with saved definitions and activation state.

No separate webhook-request or event table is required. Existing `executions`, `node_executions`, and `execution_logs` persist webhook runs and their sanitized trigger input. `ExecutionTriggerType.WEBHOOK` is stored in the existing string column.

## Frontend changes required

- Keep/register Webhook Trigger in the node library and add a real configuration panel for method, authentication mode, secret generation/regeneration, effective active state, generated URL, copy action, and test instructions.
- Replace the placeholder URL with backend registration metadata. Keep plaintext secret only in transient component state after the one-time response; mask it afterward and never persist it in workflow definitions or browser storage.
- Display inactive, active, missing-secret/configuration, and waiting-for-request states based on backend metadata, not client-only configuration.
- Add API service methods and response models for safe registration metadata and explicit secret rotation.
- Preserve the workflow definition when toggling activation. The existing dashboard activation path currently submits an empty definition, and the editor currently saves with `active: true`; both conflict with reliable registration synchronization and need narrowly scoped corrections during implementation.
- Show `WEBHOOK` as an execution trigger in existing history/details and display the sanitized trigger-node input through existing node execution records.

## Smallest compatible implementation

1. Permit exactly one root trigger that is either `manual-trigger` or `webhook-trigger`; continue rejecting multiple trigger nodes and preserve the existing manual-only execution endpoint behavior.
2. Register a pass-through `WebhookTriggerExecutor`, mirroring the manual executor so the existing runtime handles incoming input unchanged.
3. Refactor execution creation only enough to accept an internal trigger type and input while sharing graph validation, immutable snapshots, persistence, tracking, dispatch, failure handling, and limits. Keep public manual execution entry points using `MANUAL`.
4. Add a webhook-specific service path that can start a `WEBHOOK` execution only after the inbound controller has resolved and authenticated an enabled registration.
5. Add registration persistence and transactional reconciliation to workflow create/update/delete, preserving stable IDs and hashes across saves.
6. Add the inbound controller and bounded request parsing, safe payload construction, and local rate limit. Do not add a second runtime or execute work in the HTTP request thread.

## Implementation stages

1. **Inspection (complete):** Record existing trigger, workflow, runtime, persistence, frontend, security, and API behavior. No application source changes have been made.
2. **Registration/security:** Add migration/entity/repository, stable random IDs and secret hashing, transactional workflow synchronization, safe metadata/secret-rotation APIs, bounded inbound parsing, and rate limiting.
3. **Runtime integration:** Add the webhook trigger enum/executor and graph support; reuse existing execution persistence and after-commit asynchronous dispatcher; verify history and trigger input.
4. **Frontend:** Add configuration and state backed by registration APIs, secret handling, copy/test UX, preserve active workflow definitions, and surface webhook executions.
5. **Testing/docs:** Add deterministic backend/frontend coverage, update `docs/runtime/WEBHOOK_TRIGGER.md` and `docs/runtime/EXECUTION_ENGINE.md`, run backend and frontend tests/builds, and perform local manual request verification.

## Architectural risks and limitations

- The approved shared administrator token is a single-principal policy; it does not provide per-user identity, roles, audit attribution, or revocation of individual users. It must be provisioned securely and never shipped in the frontend bundle or persistent browser storage.
- Activation and save operations currently have frontend behavior that can overwrite the workflow definition. Fixing those call sites is directly required for safe webhook registration synchronization.
- The frontend's existing webhook URL is a placeholder and cannot represent a backend-owned stable endpoint. Node identity plus persistent backend registration is needed.
- Enabling a workflow with Secret Token authentication but no configured secret must not silently permit unauthenticated triggering. The registration should remain disabled and surface missing configuration until a secret is explicitly generated.
- Request-size enforcement must occur during stream reading. The existing `ExecutionService` cap is post-parse and is not sufficient by itself for inbound request memory protection.
- A single-instance in-memory limiter does not coordinate across application replicas. Exactly-once delivery is not provided; repeated deliveries create separate executions unless a later idempotency mechanism is added.
- The current runtime's validator requires one trigger per workflow. This plan supports one manual *or* one webhook root, not multiple trigger roots in a single workflow, avoiding broader scheduler/runtime changes.
- Webhook trigger input is persisted and visible in existing node execution details. Sanitization and size limits therefore need to apply before execution creation; no raw sensitive headers or secret may be included.

## Inspection conclusion

The existing runtime already provides the necessary asynchronous execution, snapshotting, tracking, cancellation/recovery, and history. The milestone can be implemented without a second execution mechanism. The essential backend additions are persistent server-owned registration, safe authentication/activation lifecycle, bounded ingress, webhook trigger validation/execution type, and an overload of the current execution-start path. The essential frontend corrections are replacing the placeholder URL, providing backend-backed secret/configuration controls, and preventing activation/save from replacing the stored definition.

# Milestone 9.4 — Execution Control and Reliability Plan

## Scope and implementation status

This is a planning document based on inspection of the current backend execution lifecycle, schema, API and Angular execution screens. No application source files were changed for this plan.

The backend already has a single Spring Boot application, asynchronous in-process dispatch, immutable workflow snapshots, independent state-service transactions, node status persistence, an execution `CANCELLED` terminal status, and a five-minute runtime deadline. It does **not** yet have cancellation or retry endpoints, durable dispatch/recovery, configurable timeouts, or an active execution-history UI backed by the execution APIs. The history page's cancel/retry actions are currently local mock-data mutations.

## 1. Current architecture and implementation findings

### Backend lifecycle

- `ExecutionService.startExecution` (`backend/src/main/java/com/flowforge/service/ExecutionService.java`) snapshots workflow name/definition and trigger input, validates the graph, writes the execution/node records/log, and dispatches the execution ID after transaction commit.
- `WorkflowExecutionWorker` receives an in-process Spring event and calls `WorkflowExecutionRuntime.execute`. It catches unexpected runtime exceptions and attempts to record a failure.
- `ExecutionAsyncConfiguration` (`backend/src/main/java/com/flowforge/config/ExecutionAsyncConfiguration.java`) provides a bounded local `ThreadPoolTaskExecutor`: core 2, max 4, queue 100; shutdown waits at most 10 seconds. The queue is memory-only.
- `ExecutionStateService` performs short transactions to transition execution/node statuses and persist outputs/logs. The runtime does not hold a transaction open for the entire workflow.
- `WorkflowExecutionRuntime` schedules validated graph nodes sequentially on a worker thread. It preserves successful node outputs, skips pending nodes after a failure, and executes conditional branches/rejoins through the active-edge graph plan.
- Execution reads return status, timestamps, duration, error, per-node status/input/output and logs through existing endpoints.

### Current status/schema behavior

- `ExecutionStatus` already contains `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, and `CANCELLED`. `Execution.transitionTo` allows queued/running to `CANCELLED` and sets completion/duration. The `V2__create_execution_tables.sql` status check constraint already permits `CANCELLED`; that status does not need a migration.
- There is no `CANCELLING` status, cancellation request flag, cancellation token, ownership/lease, optimistic version, timeout field, or retry lineage field.
- `NodeExecutionStatus` has no `CANCELLED`; the SQL constraint likewise has no such value.
- Execution records already keep `workflow_definition` and `trigger_input` as JSONB. Retry can use these immutable stored fields without consulting the potentially edited workflow.
- Node outputs and logs are independent records; no existing retry or cancellation semantics are implemented at this layer.

### Current API and UI

Current APIs in `ExecutionController`:

| Method and path | Behavior |
|---|---|
| `POST /api/workflows/{workflowId}/executions` | Start execution with optional `triggerInput`; returns 202 and `QUEUED` response |
| `GET /api/executions/{executionId}` | Read execution status/details |
| `GET /api/workflows/{workflowId}/executions` | Paginated history |
| `GET /api/executions/{executionId}/nodes` | Node records |
| `GET /api/executions/{executionId}/logs` | Execution logs |

`ExecutionApiService` wraps these endpoints but has no cancel/retry methods. `ExecutionDetailsComponent` polls the execution, nodes and logs every 1.5 seconds while status is `QUEUED` or `RUNNING`. It displays status/error/times/nodes/logs and has no Cancel/Retry controls or live duration ticker.

`ExecutionsPageComponent` currently renders `executionSeed` mock records. Its `retryExecution`, `cancelExecution`, and `refreshExecutions` mutate/refresh only local mock signals; retry claims to restart from a checkpoint, which is not supported by the backend. The page has no `ExecutionApiService` integration. This must be replaced or explicitly scoped to real API data for milestone actions to be functional.

### Existing gaps and reliability problems

1. A queued event can be lost when the process exits because dispatch is only in memory. Persisted QUEUED executions are not redispatched at startup or through a polling recovery task.
2. A process restart during RUNNING leaves an execution row RUNNING and node rows possibly RUNNING indefinitely. There is no lease/heartbeat/reconciliation.
3. Runtime deadline is hard-coded at five minutes. It is checked before/after executor calls and by `ExecutionContext.log`; a blocking/non-cooperative executor cannot be stopped while it is running.
4. No cancellation flag or worker checkpoint exists. Simply setting the execution status CANCELLED would race with a worker completing a node or marking the execution SUCCEEDED.
5. Entity state transitions are performed after repository reads without a lock or version. Worker completion, cancellation, timeout, dispatch failure, and recovery could race and overwrite terminal state.
6. Retry must be a new execution, but no endpoint or source-execution relationship exists. Existing mock UI behavior is not a real retry.
7. `Execution` completion duration is derived from start to completion; queued cancellation currently fills `startedAt` with creation time. This behavior should be corrected/defined before exposing durations.

## 2. Proposed lifecycle and state-transition rules

Keep terminal statuses `SUCCEEDED`, `FAILED`, and `CANCELLED`. Add an intermediate `CANCELLING` status to represent an accepted cancellation while a synchronous node is still running. This avoids returning a terminal CANCELLED state while a node may still be making side effects.

Proposed allowed transitions:

| Current | Event | Next | Rules |
|---|---|---|---|
| QUEUED | worker atomically claims | RUNNING | Exactly one worker can claim |
| QUEUED | cancel request | CANCELLED | Do not dispatch; pending node rows become SKIPPED |
| RUNNING | cancel request | CANCELLING | Set cancellation request atomically; worker stops at next safe checkpoint |
| CANCELLING | running node returns | CANCELLED | Persist that node's actual terminal result, skip remaining pending nodes, append cancellation log |
| RUNNING | all nodes complete | SUCCEEDED | Only if cancellation was not requested |
| RUNNING | node failure / unrecoverable runtime failure | FAILED | Preserve completed outputs; skip pending nodes |
| RUNNING | deadline expires | FAILED | Error code/message identifies timeout; stop scheduling |
| CANCELLING | worker observes cancellation between nodes | CANCELLED | No new node starts |
| Terminal | duplicate cancel/retry request | unchanged/rejected | Never mutate terminal execution or its existing logs/nodes |

No separate `TIMED_OUT` status is recommended for the first iteration. Treat timeout as `FAILED` with a stable machine-readable `errorCode: "EXECUTION_TIMEOUT"` and a safe message; consider a dedicated status only if reporting/product needs justify a status migration.

Cancellation is cooperative, not rollback. If a node is already executing, allow it to return or reach its own configured I/O timeout; do not mark a still-running node SKIPPED or claim its side effects were undone. On cancellation, record the current node's real outcome, do not start a later node, and mark remaining PENDING nodes SKIPPED. Cancellation is successful only when no node remains RUNNING.

Use the execution's persisted status/request as the cross-thread cancellation signal. The runtime checks for CANCELLING:

- before it starts a node;
- after a node returns and before recording/scheduling successors;
- before setting overall success;
- in failure/timeout finalization, so an already requested cancellation is not overwritten as FAILED or SUCCEEDED without an explicit precedence rule.

## 3. Proposed API changes

Keep existing endpoints/contracts and add:

### Cancel

`POST /api/executions/{executionId}/cancel`

- Queued execution: atomically transition directly to CANCELLED, skip pending nodes, append an audit log, return 200 with the current `ExecutionResponse`.
- Running execution: atomically transition to CANCELLING and append a cancellation-request log, return 202 with current execution state.
- Already CANCELLING: idempotently return 202/current state.
- Terminal execution: return 409 (or return 200 unchanged if product prefers idempotent terminal cancellation); never alter node/output/log history.
- Unknown execution: 404.

If execution ownership/authorization is introduced later, enforce it here; current inspected API has no authorization behavior to extend.

### Retry

`POST /api/executions/{executionId}/retry`

- Only FAILED executions are retryable in this milestone; other statuses return 409. Do not retry a CANCELLED run without an explicit product decision.
- Create a **new** execution ID, fresh PENDING node records, and a new queue log. Preserve the original record and logs unchanged.
- Use the original execution's `workflowDefinition` snapshot and original `triggerInput`, not the current workflow definition and not the original execution's partial node outputs.
- Return 202 `ExecutionStartResponse` with the new ID and QUEUED status. The retry points back to its source execution for lineage.
- Validate the immutable snapshot before creating the retry; if it is no longer runnable under current runtime validation, return a clear conflict/invalid request and do not mutate the source execution.

The existing GET responses already expose status, timestamps, duration, and `errorMessage`. Add only optional response fields needed for reliability:

- `errorCode` (timeout, shutdown interruption, executor failure, cancellation, dispatch failure).
- `retryOfExecutionId` (and optionally an integer attempt number derived from parent links).
- `timeoutSeconds` (diagnostic/configuration field).

Do not expose stack traces or arbitrary exception messages from unknown executors. Keep safe human-readable messages and stable error codes.

## 4. Database changes

The existing `CANCELLED` execution status requires no DB change. A migration is expected for:

1. **`CANCELLING`**: update the `executions` status check constraint to allow it. No node status addition is required if the current node is allowed to finish and only pending node rows are marked SKIPPED.
2. **`timeout_seconds`** (integer, non-null, default 300): persist the effective timeout with the immutable execution so a retry can reuse it and changes to defaults do not alter active runs.
3. **`retry_of_execution_id`** (nullable UUID self-reference to `executions.id`): records retry lineage without modifying the source. Add an index if querying retry chains is expected.
4. **Concurrency control**: add `@Version` and a `version` column to `Execution`, or use explicit conditional/locking repository updates. Prefer optimistic versioning plus a narrow transactional state service; for claim/cancel race-critical transitions, use atomic conditional updates or pessimistic row locking. Do not rely on an unlocked read-then-write.
5. **Optional recovery metadata**: if supporting more than one app instance or lease-based recovery is required, add `worker_id`, `heartbeat_at`/`lease_until`, and an attempt/claim token. For the existing single-process deployment, a startup recovery reconciler can be simpler, but must be protected against multiple application instances.

For upgrade safety, migrate existing rows to `timeout_seconds = 300`; existing `CANCELLED` rows remain valid. No changes to workflow JSONB, node outputs, historical execution snapshots, or logs are required.

## 5. Backend implementation steps

### Stage A — State model and concurrency primitives

1. Add `CANCELLING` and explicit timeout/retry/error metadata to execution model/DTOs and Flyway migration.
2. Define legal transition logic centrally; update entity methods and status tests. Terminal transitions must be compare-and-set/locked so only one outcome wins.
3. Add repository operations for atomic claim (`QUEUED -> RUNNING`), request cancel (`QUEUED -> CANCELLED` or `RUNNING -> CANCELLING`), and terminal completion conditioned on current state/version.
4. Make completion/failure/cancellation update node rows and execution log in short transactions. Ensure methods are idempotent where event redelivery/races are expected.

### Stage B — Cancellation and timeout-aware runtime

1. Add the cancel service/API and cancellation log. For queued cancellation, mark all PENDING node records SKIPPED.
2. Add a cancellation/deadline abstraction to `ExecutionContext`; retain the existing deadline but derive it from persisted timeout.
3. Add runtime cancellation checkpoints before node start, after node return, before outgoing-edge scheduling, and before success finalization. Preserve linear, fan-out, conditional, and join scheduling.
4. On cancellation, persist current node result accurately, skip remaining PENDING nodes, and atomically finalize CANCELLED.
5. Make timeout produce `FAILED`/`EXECUTION_TIMEOUT`, fail a currently executing node appropriately if it cannot complete, preserve prior successful outputs/logs, and skip successors.
6. Bound node executor work and require interrupt/cooperative cancellation plus outbound client timeouts for any future blocking executors. Do not claim Java thread interruption can forcibly stop arbitrary non-cooperative code.

### Stage C — Retry and failure reporting

1. Add retry endpoint/service that loads source execution snapshot/input/timeout and creates new execution/node/log records and lineage.
2. Reuse shared execution-creation logic without reading the current workflow definition for retry; preserve the workflow FK but treat saved snapshot as authoritative.
3. Define failure codes for graph/config validation, node failure, timeout, dispatch failure, cancellation, and process shutdown. Preserve existing sanitized messages.
4. Ensure retry never edits source records and never resumes from partial outputs; it re-executes from the trigger with the original input.

### Stage D — Dispatch durability and restart recovery

1. Keep the existing bounded in-process dispatcher; do not add Redis/broker.
2. Add a recovery component that periodically scans QUEUED records and dispatches them, using atomic worker claims to tolerate duplicate dispatch.
3. On startup, identify prior RUNNING/CANCELLING executions from a prior process. Mark them FAILED with `EXECUTION_INTERRUPTED` (or CANCELLED if a cancel was already pending), preserve prior SUCCEEDED nodes, mark any RUNNING node FAILED with safe interruption detail, and mark remaining PENDING nodes SKIPPED.
4. For a known local worker being shut down gracefully, request cancellation/interruption and wait only within configured grace. Do not automatically replay a RUNNING execution: node side effects may have occurred and replay could duplicate them.
5. Emit a recovery log for every repaired execution and ensure terminal records cannot be reopened.
6. If multiple application instances are a deployment target, do not use unconditional startup RUNNING cleanup. Use leases/heartbeats and owner identity so one instance cannot mark another live worker interrupted.

### Stage E — API and regression integration

1. Keep current start/list/detail/node/log endpoints intact.
2. Add response fields additively and test API error semantics.
3. Test the order/race between cancel, node success/failure, timeout, and overall success; terminal outcome must be deterministic and logged once.
4. Keep every state update and repository transaction short; never wrap external node work in a transaction.

## 6. Frontend implementation steps

### Execution details

- Add Cancel action for QUEUED, RUNNING (and CANCELLING as “Cancellation requested” state); disable duplicate submissions and refresh/poll.
- Add Retry action for FAILED only, opening or starting a new execution and navigating/showing its new ID. Do not label it “resume from checkpoint.”
- Show duration live while queued/running using the server start time and a timer; use persisted `durationMs` for terminal states.
- Show safe failure message and error code; distinguish FAILED, TIMED OUT (failed + timeout code), CANCELLING, CANCELLED, and SKIPPED nodes.
- Keep polling detail data while QUEUED/RUNNING/CANCELLING. Stop only at terminal states; refresh immediately after actions.

### Execution history page

- Replace local mock mutation actions with `ExecutionApiService` calls and backend history/detail data. If a product requirement keeps mock records, isolate them from real records and do not show functional Cancel/Retry affordances for mocks.
- Integrate paginated `GET /api/workflows/{workflowId}/executions`; clarify the route/global-history behavior because the current API lists executions per workflow while the page is an all-workflow mock.
- Add API service methods for cancel/retry and update status/duration/failure data from server responses.
- Use RxJS polling or a shared refresh signal to update history and selected details without full-page reload. Avoid duplicate independent pollers when details component is active.
- Preserve existing search/filter/sorting/pagination behavior over actual records or clearly scoped server paging.

### Likely frontend files

- `src/app/core/models/execution.model.ts`
- `src/app/core/services/execution-api.service.ts`
- `src/app/features/executions/execution-details.ts`
- `src/app/features/executions/executions-page.ts`
- Associated specs for API methods, cancellation/retry controls, polling, error states, terminal statuses, live duration, and history refresh.

## 7. Configurable timeout design

Recommended first version: optional per-execution `timeoutSeconds` in start request, validated to a bounded range (proposed 1–3600 seconds), default 300 seconds. Persist the effective value on the execution record and include it on retry by default. This is additive to current `{ "triggerInput": ... }`; absent timeout preserves existing request behavior/default.

Timeout covers time from RUNNING start through workflow completion, not queue wait. Queue wait can be separately monitored; do not consume execution timeout before the worker claims it. On deadline, do not schedule the next node. If the current executor is synchronous and not cooperative, completion/terminalization can be delayed; therefore each blocking integration must have its own I/O deadline, and the worker should use interruption as a best-effort signal.

Alternative requiring approval: workflow-level timeout stored in the definition or application-wide configuration. Per-run request is simplest and allows retry to preserve the original effective timeout, but raises a question about who can select a long timeout and whether the UI exposes it.

## 8. Recovery and concurrency considerations

- **Cancel vs node completion:** serialize through a versioned/locked execution transition and check cancellation before storing/scheduling next work. If the executor has already performed an external side effect, cancellation cannot undo it.
- **Cancel vs terminal success/failure:** first valid terminal transition wins. A late cancel of terminal executions must not rewrite them.
- **Duplicate queued events:** worker claim must be atomic, so duplicate dispatch events cannot start a second runtime.
- **Worker crash/restart:** RUNNING is not evidence that work can safely resume. Mark interrupted runs failed; do not replay without node idempotency/side-effect guarantees.
- **Queued recovery:** periodic scan should redispatch old QUEUED rows; worker claim protects from duplicate queue events.
- **Graceful shutdown:** current 10-second await is shorter than the five-minute execution limit and does not guarantee durable work. Shutdown behavior must be explicit, bounded, and reflected in state.
- **Multi-instance deployment:** either explicitly support a single app instance for recovery or add leases/worker identity. Unconditional stale-RUNNING recovery is unsafe in a multi-instance deployment.
- **Node cancellation:** Java cannot safely terminate arbitrary threads. Cancellation/timeout is cooperative at node boundaries; executors should honor interruption and use bounded external calls. Hard termination requires process isolation and is outside this milestone's no-extra-infrastructure constraint.
- **Preserving history:** retry must create new rows and never change original execution/node/log rows. FK cascade on workflow deletion currently deletes execution history; decide whether that existing behavior remains in scope or is a separate retention concern.

## 9. Test strategy

### Backend unit/state tests

- Legal/illegal transitions including QUEUED cancellation, RUNNING to CANCELLING to CANCELLED, and terminal immutability.
- Concurrent cancel/complete/fail/timeout attempts; assert exactly one terminal result and no duplicate terminal logs.
- Retry lineage/defaults and immutable snapshot/trigger input reuse.
- Timeout configuration bounds/default and timeout error codes.
- Idempotent cancellation and retry of non-FAILED runs rejected.
- Node-state reconciliation: current running node outcome, pending-to-SKIPPED transitions, preservation of prior successful outputs.

### Backend API/integration tests

- Cancel queued before worker claims: no node starts, pending nodes skipped, terminal CANCELLED.
- Cancel running between nodes: current node succeeds, no later node starts, execution CANCELLED.
- Cancel during a blocking test executor: cancellation requested, running node not falsely marked skipped, worker exits at safe boundary and terminalizes.
- Cancel racing final success or node failure: deterministic legal terminal outcome; no permanent CANCELLING.
- Retry FAILED run after editing the current workflow: new run uses original snapshot/input, gets a new ID, preserves source and logs.
- Retry SUCCEEDED/RUNNING/QUEUED/CANCELLED returns specified conflict.
- Timeout before first node, between nodes, and during a cooperative blocking executor; verify error code, terminal FAILED, outputs preserved, pending nodes skipped.
- Non-cooperative executor limitation documented/tested as best-effort only; bounded I/O executor example should honor interruption.
- Recovery of queued execution redispatches exactly once despite duplicate scan/event.
- Recovery of RUNNING execution marks interrupted run terminal and reconciles node states without replay.
- Multi-instance/lease behavior if multi-instance support is selected.
- Re-run existing sequential, conditional, join, unconditional fan-out, data passing, failure, and async dispatch integration tests.

### Frontend tests

- Cancel visible/enabled only for active statuses; repeated click disabled; response updates status and errors remain visible.
- Retry visible/enabled only for FAILED; uses API and selects the newly created ID rather than mutating original row.
- Polling updates queued/running/cancelling state and stops at terminal states.
- Live duration updates while active and server `durationMs` used when terminal.
- Failure code/message display and `CANCELLED`/`CANCELLING` status styling.
- History API paging/search/filter/sort/selection and refresh after cancel/retry.
- Preserve current workflow execution details and conditional SKIPPED/decision monitoring.

## 10. Risks and design decisions requiring approval

1. **Intermediate cancellation status:** approve adding `CANCELLING` (recommended), or require immediate `CANCELLED` status even though a running synchronous node may still be executing.
2. **Active-node cancellation semantics:** recommended: let current node finish or honor its own cooperative timeout, then stop before the next node. Confirm whether cancellation should interrupt the current node best-effort.
3. **Timeout configuration:** approve optional per-execution timeout with 300-second default and 1–3600-second bound, or choose workflow/global configuration. Decide whether frontend exposes it.
4. **Timeout terminal status:** recommended `FAILED` + `EXECUTION_TIMEOUT` rather than a new `TIMED_OUT` status. Confirm whether a dedicated terminal status is required for analytics.
5. **Retry semantics:** recommended only FAILED runs retryable, with new execution based on original immutable snapshot, original trigger input, and original timeout, replaying from the trigger (not checkpoint). Confirm whether CANCELLED or timed-out failures are retryable; timeout can be treated as FAILED.
6. **Recovery model:** recommended startup recovery plus periodic queued redispatch for a single-instance deployment, marking prior RUNNING as interrupted FAILED and never replaying it automatically. Confirm whether multi-instance deployments must be supported now; that requires leases/heartbeats/owner IDs.
7. **Reliability guarantee:** an in-memory executor cannot guarantee exactly-once side effects across process crashes. This plan provides durable records and safe failure/retry behavior, not transactional external side effects.
8. **Execution history page scope:** current backend history is workflow-scoped but the Angular page is global mock data. Decide whether Milestone 9.4 also adds a global history endpoint or adapts the page to a selected workflow / known workflows list.
9. **Authorization:** no execution ownership/authorization contract was found in the inspected API. Confirm whether cancel/retry require user-level authorization in this milestone.

## 11. Recommended implementation order

1. Approve lifecycle/timeout/retry/recovery semantics and establish a single-instance vs multi-instance assumption.
2. Add migration/model/status concurrency primitives, then test state transitions and race outcomes.
3. Implement safe cancellation checkpoints and execution timeouts; test cancellation against the existing branching/join scheduler before adding UI.
4. Add retry as a new immutable execution from the stored snapshot/input and test source-history preservation.
5. Add queued dispatch recovery and startup reconciliation; test restart scenarios and duplicate delivery.
6. Add API endpoints/DTO fields and integration coverage.
7. Connect API service, execution details, history actions, live status/duration and errors in Angular.
8. Run backend clean tests/build and frontend tests/production build; perform manual/API acceptance for cancellation, retry, timeout and restart recovery.


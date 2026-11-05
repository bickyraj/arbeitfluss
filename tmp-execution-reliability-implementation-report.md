# Milestone 9.4 — Execution Control and Reliability Implementation Report

## Status

The implementation and automated verification are complete for the available local test environment. **Milestone 9.4 is not marked COMPLETED yet**: the Flyway migration was not applied to a live PostgreSQL database, and browser-driven/manual acceptance against a running frontend and backend was not performed.

## Implemented features

- Added the `CANCELLING` lifecycle status. State changes use pessimistic execution-row locking for worker claims, cancellation, terminalization, retries, and recovery. Duplicate cancellation during `CANCELLING` is idempotent; terminal cancellation is rejected.
- Queued cancellation immediately marks pending nodes SKIPPED and execution CANCELLED. Running cancellation is cooperative: the active node is allowed to finish, then pending nodes are skipped and the execution becomes CANCELLED. A running node is not falsely marked skipped.
- Added per-execution `timeoutSeconds`, default 300 and restricted to 1–3600. The execution deadline begins when the worker claims the run. Node work runs in an interruptible virtual thread; timeout interrupts the node and terminalizes the execution as FAILED with `EXECUTION_TIMEOUT`.
- Added retry for FAILED executions only. Each retry creates new execution/node/log records, uses the original snapshot, trigger input, and timeout, and records `retryOfExecutionId`. It starts from the trigger; no checkpoint/partial-output resume occurs.
- Added single-instance startup recovery: prior RUNNING executions become FAILED/`EXECUTION_INTERRUPTED`; prior CANCELLING executions become CANCELLED. Completed outputs are preserved, interrupted nodes are reconciled, and pending nodes are skipped. QUEUED records are redispatched at startup and by a scheduled database scan.
- Added global paginated execution history, with workflow-name search, status filtering, and supported sorting. The Angular history page now uses backend records instead of seed data; execution details poll status, show duration/error codes, and expose real Cancel/Retry controls.
- Added timeout selection to the workflow editor's existing execution dialog.
- Preserved existing sequential, fan-out, conditional branching/join, and data-passing behavior. Documentation explicitly describes cooperative cancellation, repeatable side effects, recovery assumptions, and the lack of exactly-once guarantees.

## API contract

- `POST /api/workflows/{workflowId}/executions`
  - Request: `{"triggerInput": {...}, "timeoutSeconds": 300}`; timeout is optional and defaults to 300.
  - Timeout must be an integer from 1 through 3600.
- `POST /api/executions/{executionId}/cancel`
  - Queued: HTTP 200, status `CANCELLED`.
  - Running: HTTP 202, status `CANCELLING`; terminal status follows after the active node finishes or times out.
  - Terminal run: HTTP 409.
- `POST /api/executions/{executionId}/retry`
  - FAILED only; HTTP 202 with a new execution ID and QUEUED status. Other statuses return HTTP 409.
- `GET /api/executions?page=0&size=20&search=...&status=FAILED&sort=newest`
  - Global paginated history. `search` matches workflow name; `status` is optional; `sort` accepts `newest`, `oldest`, `duration-desc`, or `status`; maximum page size is 100.
- Execution responses now include `errorCode`, `timeoutSeconds`, and nullable `retryOfExecutionId`.

## Database migration

Added `backend/src/main/resources/db/migration/V3__execution_reliability.sql`:

- Extends the execution status check constraint with `CANCELLING`.
- Adds `error_code`, `timeout_seconds` (default 300), and nullable self-referencing `retry_of_execution_id`, with an index for retry lineage.
- No node status or workflow snapshot schema changes.

The backend integration-test profile disables Flyway and uses Hibernate-created H2 schema, so this migration has **not** been executed against PostgreSQL in this task.

## Files changed

### Backend

- `backend/src/main/java/com/flowforge/config/ExecutionAsyncConfiguration.java`
- `backend/src/main/java/com/flowforge/controller/ExecutionController.java`
- `backend/src/main/java/com/flowforge/dto/execution/ExecutionRequest.java`
- `backend/src/main/java/com/flowforge/dto/execution/ExecutionResponse.java`
- `backend/src/main/java/com/flowforge/entity/Execution.java`
- `backend/src/main/java/com/flowforge/exception/ApiExceptionHandler.java`
- `backend/src/main/java/com/flowforge/exception/ExecutionConflictException.java` (new)
- `backend/src/main/java/com/flowforge/model/execution/ExecutionRunSnapshot.java`
- `backend/src/main/java/com/flowforge/model/execution/ExecutionStatus.java`
- `backend/src/main/java/com/flowforge/repository/ExecutionRepository.java`
- `backend/src/main/java/com/flowforge/repository/NodeExecutionRepository.java`
- `backend/src/main/java/com/flowforge/runtime/WorkflowExecutionRuntime.java`
- `backend/src/main/java/com/flowforge/runtime/WorkflowExecutionWorker.java`
- `backend/src/main/java/com/flowforge/service/ExecutionDispatchFailureRecorder.java`
- `backend/src/main/java/com/flowforge/service/ExecutionRecoveryService.java` (new)
- `backend/src/main/java/com/flowforge/service/ExecutionService.java`
- `backend/src/main/java/com/flowforge/service/ExecutionStateService.java`
- `backend/src/main/resources/db/migration/V3__execution_reliability.sql` (new)

### Frontend and documentation

- `src/app/core/models/execution.model.ts`
- `src/app/core/services/execution-api.service.ts`
- `src/app/core/services/execution-api.service.spec.ts`
- `src/app/features/executions/execution-details.ts`
- `src/app/features/executions/execution-details.spec.ts`
- `src/app/features/executions/executions-page.ts`
- `src/app/features/executions/executions-page.spec.ts` (new)
- `src/app/features/workflows/workflow-editor/workflow-editor.ts`
- `src/app/features/workflows/workflow-editor/workflow-editor.spec.ts`
- `docs/runtime/EXECUTION_ENGINE.md`
- `tmp-execution-reliability-implementation-report.md` (this report)

## Tests and build results

| Command | Result |
|---|---|
| `cd backend && ./gradlew build` | PASS — 30 backend tests passed; Gradle `build` successful, including `bootJar` |
| `npm test -- --watch=false` | PASS — 22 tests across 6 files |
| `npm run build` | PASS — Angular production bundle generated |
| `git diff --check` | PASS |

Backend coverage added/updated for queued cancellation, concurrent idempotent running cancellation, retry snapshot/input/timeout preservation and eligibility, timeout terminalization and downstream skipping, interrupted-run reconciliation, queued redispatch, status transitions, and error reporting. Existing sequential/data-flow, fan-out, conditional branch/join, nested conditional, and failure tests also passed.

Frontend coverage includes API request contracts, global history rendering from real API data, retry/cancel controls, and timeout validation/submission.

## Known limitations and remaining manual verification

- Cancellation and timeout cannot roll back external side effects. Retries rerun the full snapshot/input and can repeat prior effects; exactly-once external execution is not provided.
- `Future.cancel(true)` interrupts executor code but Java cannot safely force-stop arbitrary non-cooperative code. Such code may continue after the run is marked timed out; future integrations must honor interruption and use bounded I/O timeouts.
- Recovery intentionally supports the approved single-instance model only. There are no leases or multi-instance coordination.
- The PostgreSQL Flyway migration needs validation against the deployment database before release.
- Manual acceptance remains: apply V3 on PostgreSQL, exercise queued/running cancellation and retry through the browser, verify details/history refresh against the running API, and confirm restart recovery with persisted QUEUED/RUNNING records.


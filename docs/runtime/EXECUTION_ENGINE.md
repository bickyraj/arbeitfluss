# Workflow Execution Engine

## Validation and order

Execution uses an immutable workflow snapshot. `WorkflowGraphValidator` validates node types/configuration, handles, references, duplicate edges, trigger count, cycles, and static reachability before queueing. It builds an edge-aware DAG and deterministic topological execution order. Multiple incoming edges are retained for rejoining.

## Active-edge scheduling

The runtime resolves outgoing edges after a node succeeds:

- Ordinary executor: all outgoing `output` edges become active, preserving sequential and unconditional fan-out behavior.
- `if-condition`: only edges on the evaluator-selected `true` or `false` handle become active; the other branch edges are inactive.
- A node whose incoming edges are all inactive is `SKIPPED`; its outgoing edges become inactive.
- A node with one or more active incoming edges executes once after all predecessors have resolved. Topological ordering provides the completion barrier.

This algorithm supports nested conditional paths and fan-out. It does not infer a decision from edge order.

## Joins

A join runs once if any incoming edge is active. Inactive edges do not block it; all-inactive joins are skipped. Exactly one active predecessor retains the existing upstream output as input. For multiple active predecessors, input is an `inputs` object keyed by immediate predecessor node ID, ordered by topological source-node order. `ExecutionContext.upstreamOutputs()` contains deep copies of these direct inputs. No arbitrary predecessor selection or last-write-wins merge is used.

## Status and failure behavior

Executed nodes move through existing PENDING/RUNNING/SUCCEEDED or FAILED statuses. Nodes on inactive-only paths become SKIPPED, distinct from failure. A node failure stops further execution, preserves completed node results/logs, skips remaining pending nodes, and fails the overall execution. Condition configuration is checked before execution queueing; missing runtime input fields or incompatible actual values fail the condition node.

## Decisions and logs

The IF/ELSE node emits an INFO log with `nodeType`, `field`, `operator`, `result`, and `selectedBranch`. Actual/comparison values are excluded to avoid logging sensitive data. Execution details display the log and skipped-node status through the existing API.

## Execution reliability and controls

Execution records use `QUEUED`, `RUNNING`, `CANCELLING`, and the terminal states `SUCCEEDED`, `FAILED`, and `CANCELLED`. State-changing service operations lock the execution row before applying transitions, so worker claims, cancellation, timeout, failure, and recovery cannot overwrite each other's terminal state. A worker claim only moves a still-QUEUED record to RUNNING; duplicate dispatches are harmless.

### API contract

- `POST /api/workflows/{workflowId}/executions` accepts `{"triggerInput": {...}, "timeoutSeconds": 300}`. `timeoutSeconds` is optional, defaults to 300, and must be an integer from 1 through 3600.
- `POST /api/executions/{executionId}/cancel` returns `CANCELLED` for an execution cancelled while queued (HTTP 200), or `CANCELLING` for an active worker (HTTP 202). Repeated requests while cancelling are idempotent. Terminal executions reject cancellation with HTTP 409.
- Cancellation is cooperative: a running node is allowed to finish or reach its timeout, and no subsequent node is scheduled. A running node is never falsely marked SKIPPED. Cancellation cannot undo external side effects already performed by a node.
- `POST /api/executions/{executionId}/retry` accepts only FAILED executions and creates a new execution ID, node records, and logs. It reruns the original immutable workflow snapshot with the original trigger input and timeout; it does not resume from partial outputs or checkpoints. `retryOfExecutionId` links the new execution to its source.
- `GET /api/executions?page=0&size=20&search=...&status=FAILED&sort=newest` provides global, paginated history. `search` matches workflow name; `status` is optional; `sort` accepts `newest`, `oldest`, `duration-desc`, or `status`. Page size is limited to 100. The existing workflow-scoped history endpoint remains available.
- Execution responses add `errorCode`, `timeoutSeconds`, and `retryOfExecutionId`. Timeout terminalizes as FAILED with `EXECUTION_TIMEOUT`; process-restart recovery uses `EXECUTION_INTERRUPTED`.

### Timeout and process recovery

The effective timeout starts when a worker claims the execution, not while it is queued. Each node runs in an interruptible virtual thread and the workflow coordinator waits only until the execution deadline; timeout cancels/interupts the node task, records FAILED with `EXECUTION_TIMEOUT`, preserves completed outputs, and skips pending nodes. Interruption is best effort: arbitrary non-cooperative executor code cannot be forcibly terminated safely, and any integration performing blocking I/O must configure its own bounded I/O timeout. Such code may continue after the workflow has been marked timed out.

On single-instance startup, QUEUED executions are redispatched and prior RUNNING executions are marked FAILED/`EXECUTION_INTERRUPTED`; prior CANCELLING executions become CANCELLED. Successful node outputs are retained, any interrupted RUNNING node is marked FAILED, and remaining PENDING nodes become SKIPPED. A scheduled scan redispatches queued records using an in-process dispatch set plus an atomic database claim. Running executions are never automatically replayed after restart because external effects may already have occurred.

The in-process dispatcher and ordinary workflow retry do not provide exactly-once external side effects. A retry is a new full execution from the beginning and may repeat work already performed by the failed run. Cancellation and timeout do not roll back external side effects. Node executor implementations must honor thread interruption and configure bounded I/O; arbitrary non-cooperative Java code cannot be forcibly terminated safely.

The HTTP Request node and its outbound-network protections, configuration and output contract are documented in [HTTP_REQUEST_NODE.md](HTTP_REQUEST_NODE.md).

Webhook Trigger requests are authenticated and resolved through persistent backend registration, then saved as `WEBHOOK` executions and dispatched through the same asynchronous execution pipeline described above. Their limits, safe input shape, rate limit, activation behavior, and response contract are documented in [WEBHOOK_TRIGGER.md](WEBHOOK_TRIGGER.md).

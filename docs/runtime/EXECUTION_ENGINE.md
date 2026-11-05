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

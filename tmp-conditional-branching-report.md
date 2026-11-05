# FlowForge Milestone 9.3 Conditional Branching — Diagnostic Report

**Scope:** Read-only source investigation. No application source, configuration, migration, or existing documentation files were changed. This report is the only file created.

## 1. Error investigation

The frontend declares `if-condition` as a `WorkflowNodeType` in `src/app/core/models/workflow-editor.model.ts:1-14`. The workflow editor adds it to the node library and its descriptor in `src/app/features/workflows/workflow-editor/workflow-editor.ts:48,93-97`. Its default descriptor configuration is `{ field: 'status', operator: 'equals', value: 'active' }`.

The saved node shape is the `WorkflowNode` model: `id`, `type`, `name`, `description`, `position: { x, y }`, and `configuration` (`workflow-editor.model.ts:16-28`). `makeNode` constructs that shape (`workflow-editor.ts:127-133`); the initial example IF node uses `id: "node-2"`, type `if-condition`, position `{ x: 420, y: 170 }`, and the default configuration (`workflow-editor.ts:141`). When saved, `saveState` places `nextState.nodes` and `nextState.connections` directly in `definition` (`workflow-editor.ts:1020-1035`).

Thus a representative saved IF node is:

```json
{
  "id": "node-1793879133573-f9cd",
  "type": "if-condition",
  "name": "IF Condition",
  "description": "Branches when a condition is true.",
  "position": { "x": 420, "y": 170 },
  "configuration": {
    "field": "status",
    "operator": "equals",
    "value": "active"
  }
}
```

The ID and position in that example are illustrative: the reported ID is known from the error, but the persisted workflow record itself was not available for inspection. Users can edit the configuration values, so the exact values stored for that particular execution cannot be established from source alone.

`WorkflowGraphValidator.validate` checks every node type with `executorRegistry.supports(type)` and throws `Node '<id>' has unsupported type '<type>'` if absent (`backend/src/main/java/com/flowforge/validation/WorkflowGraphValidator.java:53-59`). Therefore the quoted error means the execution snapshot contains the frontend's `if-condition` type, but the backend executor registry has no executor registered under that exact string.

## 2. Backend runtime

`NodeExecutorRegistry` is a Spring component that receives all Spring-managed `NodeExecutor` beans and builds a map keyed by each executor's `nodeType()`. Duplicate types fail during registry construction; `supports` and `getRequired` look up the exact type key (`backend/src/main/java/com/flowforge/executor/NodeExecutorRegistry.java:10-34`).

The current executor implementations return these exact types:

| Executor | Registered type |
|---|---|
| `ManualTriggerExecutor` | `manual-trigger` |
| `SetDataExecutor` | `set-data` |
| `LogMessageExecutor` | `log-message` |

No IF/ELSE executor is implemented in `backend/src/main/java/com/flowforge/executor/`. It is not a bean registration/name mismatch: the executor class itself is absent, and the registry has no `if-condition` entry.

The validator accepts only source handle `output` and target handle `input` (`WorkflowGraphValidator.java:21,106-111`). It records outgoing edges, but the runtime iterates `graph.executionOrder()` and executes each node in that topological order (`backend/src/main/java/com/flowforge/runtime/WorkflowExecutionRuntime.java:64-65`). It does not choose TRUE/FALSE edges; source handles have no branch-selection semantics. In addition, the graph plan stores one incoming connection per target (`backend/src/main/java/com/flowforge/runtime/WorkflowGraphPlan.java:11-15`), and the validator rejects multiple incoming connections (`WorkflowGraphValidator.java:117-120`).

## 3. Frontend implementation

The IF node exists as an editor node-library entry, a descriptor/default configuration, an icon mapping, and a configuration-panel case in `src/app/features/workflows/workflow-editor/workflow-editor.ts` (`48`, `93-97`, `416-421`, `646`). It is not implemented as a distinct branch-aware component.

All rendered nodes currently get one input handle named `input` and one output handle named `output` (`workflow-editor.ts:336-357`). No TRUE or FALSE handles/labels are rendered. The initial workflow state does include a sample IF connection using `output` and another using `output-2` (`workflow-editor.ts:145-149`), but `output-2` is not a rendered handle and is rejected by backend validation.

The configuration panel exposes a text field, an operator select (`equals`, `not equals`, `greater than`, `less than`), and a comparison-value text field (`workflow-editor.ts:416-421`). `updateSelectedNodeConfig` writes the selected control's `.value` into `node.configuration[key]` and marks state dirty (`workflow-editor.ts:701-723`). The model permits string, number, boolean, or undefined values, but these controls update values as strings; comparison values have no type selector. The node config is included in the saved `nodes` array as part of the `definition`. Persistence of the configured values is implemented; evaluation/typed comparison is not.

## 4. Execution behavior

- **Condition evaluation:** Not implemented. No conditional executor or evaluator exists.
- **Branch selection:** Not implemented. Runtime execution order is topological, and does not consult source handles to choose a path.
- **Unselected nodes as `SKIPPED`:** Not implemented for conditional branches. `WorkflowExecutionRuntime.failExecution` marks remaining pending nodes `SKIPPED` after a node failure (`WorkflowExecutionRuntime.java:157-165`), and `ExecutionStateService.skipNode` supports the pending-to-skipped transition (`backend/src/main/java/com/flowforge/service/ExecutionStateService.java:78-83`). Those mechanisms are failure handling, not conditional-branch pruning.
- **Branch rejoining:** Not supported. The validator rejects multiple incoming connections to a node (`WorkflowGraphValidator.java:117-120`).
- **Fan-out:** Supported as unconditional graph fan-out in the current DAG runtime; existing tests exercise it. It does not represent conditional selection.
- **Other missing pieces:** There is no persisted conditional decision/selected-branch result, no branch-specific execution log, and no UI presentation of a conditional decision.

## 5. Tests and verification

Relevant existing backend tests:

- `backend/src/test/java/com/flowforge/validation/WorkflowGraphValidatorTest.java`: derives topological order, tests unconditional fan-out, and rejects invalid references/handles, `output-2`, duplicates, cycles, absent trigger, unsupported `http-request`, unreachable nodes, and multiple incoming connections.
- `backend/src/test/java/com/flowforge/controller/ExecutionApiIntegrationTest.java`: connected-node execution/data passing, unconditional fan-out, and failure/skipped-node behavior.
- `backend/src/test/java/com/flowforge/controller/ExecutionDispatchIntegrationTest.java` and `backend/src/test/java/com/flowforge/runtime/WorkflowExecutionWorkerTest.java`: asynchronous dispatch and worker failure behavior.
- `backend/src/test/java/com/flowforge/service/ExecutionStatusTest.java`: status transition rules.

The inspected frontend specs contain no `if-condition`, TRUE/FALSE, or conditional-branch tests. No conditional-specific tests were found in the backend either. The validator test explicitly asserts that `output-2` is rejected.

**Tests/builds executed for this investigation:** none. No frontend build or backend test/build was run, to keep this a read-only diagnosis other than creating the requested report. Accordingly, current frontend build and backend test/build status is **NOT VERIFIED**. Prior session records mention successful builds/tests for earlier execution and drag changes, but those were not conditional-branch tests and do not verify the current worktree.

## 6. Root cause and proposed fix

**Most likely root cause:** the editor saves nodes with type `if-condition`, but the backend supports only `manual-trigger`, `set-data`, and `log-message`. The validator's executor registry check rejects the node before graph execution can proceed (`WorkflowGraphValidator.java:53-59`). The frontend's sample `output-2` edge is an additional incompatibility: backend validation accepts only `output` (`WorkflowGraphValidator.java:21,106-111`).

Files likely requiring changes for a complete fix:

1. `src/app/core/models/workflow-editor.model.ts` — model explicit conditional branch-handle identifiers and, if required, typed condition values.
2. `src/app/features/workflows/workflow-editor/workflow-editor.ts` — render and persist distinct TRUE/FALSE output handles and maintain backward compatibility when loading existing connection data.
3. `backend/src/main/java/com/flowforge/executor/` — add a conditional executor (or equivalent decision component) registered under exactly `if-condition`; define how the decision reaches graph routing rather than returning only ordinary node output.
4. `backend/src/main/java/com/flowforge/validation/WorkflowGraphValidator.java` — validate conditional-specific handles/configuration and graph invariants while considering legacy `output`/`output-2` saved workflows.
5. `backend/src/main/java/com/flowforge/runtime/WorkflowGraphPlan.java` and `WorkflowExecutionRuntime.java` — retain handle-aware edges, select only the chosen branch, mark unselected branch nodes `SKIPPED`, and define data semantics for selected-path fan-out/rejoins.
6. `backend/src/test/java/com/flowforge/validation/WorkflowGraphValidatorTest.java`, `backend/src/test/java/com/flowforge/controller/ExecutionApiIntegrationTest.java`, and new or existing Angular workflow-editor specs — cover TRUE/FALSE decisions, invalid conditions/handles, skipped branches, data passing, fan-out, rejoin semantics, persistence, and legacy definitions.
7. `src/app/features/executions/execution-details.ts` — only if the product requires showing persisted branch decisions; current UI has no such decision data to display.

Proposed implementation sequence:

1. Define and document exact TRUE/FALSE handle IDs and condition configuration/value typing, including a compatibility policy for existing `output` and `output-2` definitions.
2. Extend editor handle rendering and save/load behavior so each conditional connection's selected handle survives round trips.
3. Add backend conditional evaluation for the supported operators with explicit handling of missing fields, incompatible types, and invalid configuration; return a decision in a runtime-consumable form.
4. Make graph validation type-aware for handles and preserve edge source handles in the execution plan.
5. Change scheduling from “run all topological nodes” to selected-path execution. Define join behavior before supporting rejoining; do not silently execute a join after only one arbitrarily chosen predecessor.
6. Persist selected-branch/skip outcomes as appropriate and expose them in execution details.
7. Add focused frontend/backend tests, then run backend tests/build and frontend tests/build.

**Risks and compatibility concerns:** changing handle IDs can invalidate existing saved workflows; the sample `output-2` is already rejected. Existing graph plan/runtime assume at most one incoming edge, so rejoining cannot be added safely by merely pruning nodes. Condition values saved by the current UI are strings, which can create ambiguous numeric/boolean comparisons. Existing unconditional fan-out must remain unaffected. The specific execution snapshot for the reported node was unavailable, so its persisted configuration and connections should be captured before implementing a migration/compatibility policy.

## 7. Current implementation status

| Feature | Status | Evidence |
|---|---|---|
| `if-condition` type available in editor/model | IMPLEMENTED | `workflow-editor.model.ts:1-14`; `workflow-editor.ts:48,93-97` |
| Condition field/operator/value configuration UI | PARTIALLY IMPLEMENTED | `workflow-editor.ts:416-421`; basic controls exist, but no validation or typed comparison values |
| Condition configuration persisted in workflow definition | IMPLEMENTED | `workflow-editor.ts:701-723,1020-1035` |
| TRUE/FALSE output handles | NOT IMPLEMENTED | Only `output` rendered: `workflow-editor.ts:336-357` |
| IF/ELSE backend executor registration | NOT IMPLEMENTED | Registry uses `NodeExecutor` beans; no `if-condition` executor exists |
| Conditional-specific handle validation | NOT IMPLEMENTED | Validator only accepts `output`: `WorkflowGraphValidator.java:21,106-111` |
| Condition evaluation and branch selection | NOT IMPLEMENTED | Runtime executes topological list: `WorkflowExecutionRuntime.java:64-65` |
| Conditional unselected-node `SKIPPED` handling | NOT IMPLEMENTED | Existing skip path is failure-only: `WorkflowExecutionRuntime.java:157-165` |
| Branch rejoining | NOT IMPLEMENTED | Multiple incoming edges rejected: `WorkflowGraphValidator.java:117-120` |
| Unconditional fan-out | IMPLEMENTED | `WorkflowGraphValidatorTest.derivesExecutionOrderFromEdgesAndSupportsFanOut`; `ExecutionApiIntegrationTest.executesFanOutBranchesWithoutSharingMutatedOutput` |
| Current test/build status | NOT VERIFIED | Not run during this read-only investigation |


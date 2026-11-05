# FlowForge Milestone 9.3 — Conditional Branching Implementation Plan

## Scope and evidence

This plan is based on the diagnostic report, the Milestone 9.3 specification, and a fresh inspection of the current editor model/template, graph validator/plan/runtime, executor contract, execution state service, execution-details component, and existing validator/integration/editor tests. It proposes changes only; no application source is changed by this plan.

The relevant current contracts are:

- The editor persists nodes with `id`, `type`, `name`, `description`, `position`, and `configuration`, and connections with `id`, `sourceNodeId`, `sourceHandle`, `targetNodeId`, and `targetHandle`.
- The existing IF node type is `if-condition`; its configuration keys are `field`, `operator`, and `value`.
- The backend registry discovers Spring `NodeExecutor` beans by `nodeType()`. `WorkflowExecutionRuntime` currently executes every node in topological order, while the validator allows only `output` and `input` handles and rejects multiple incoming edges.
- Node execution records and logs are already persisted. `SKIPPED` and `ExecutionStateService.skipNode` already exist, but skip behavior is currently for failure handling only. Execution logs already have JSON details returned through the execution API and shown by `ExecutionDetailsComponent`.
- No execution/entity schema change appears necessary for the decision/skip data described below. Keep the existing single Spring Boot application and JSONB workflow snapshots.

## 1. Proposed node, configuration, and connection contracts

### Node identity and configuration

Keep the existing node type and node JSON shape to avoid an unnecessary schema migration:

```json
{
  "id": "condition-1",
  "type": "if-condition",
  "name": "IF / ELSE",
  "description": "Branches based on a condition.",
  "position": { "x": 420, "y": 170 },
  "configuration": {
    "field": "age",
    "operator": "greater than or equal",
    "value": 18
  }
}
```

`field` is a non-empty path within the current node input; support dot-separated object paths (for example `user.age`) and do not evaluate arbitrary expressions. `operator` is one of the canonical strings already used by the UI plus the additions required by the specification:

`equals`, `not equals`, `greater than`, `greater than or equal`, `less than`, `less than or equal`, `is empty`, `is not empty`.

`value` remains an optional JSON scalar (`string`, `number`, `boolean`, or `null`) rather than being coerced to text. The editor should expose its scalar type when entering comparison values, preserving the existing string type for legacy saved values. For `is empty` and `is not empty`, comparison value is unused. Do not silently coerce strings to numbers or booleans. Equality requires compatible JSON scalar types; ordering comparisons require two numbers or two strings of the same type. Boolean ordering and object/array comparisons are invalid.

Define emptiness consistently: missing field, JSON null, and an empty string count as empty; non-empty scalar values count as not empty. Arrays and objects are not accepted as comparison operands in this milestone and should produce a useful configuration/runtime error rather than an implicit result. For operators other than the two emptiness operators, a missing or null field is an evaluation error. This gives `is empty` useful semantics without making other missing-field comparisons silently false.

### Edge and handle contract

Keep the existing connection object fields. Use exact lowercase handle IDs for persistence/API compatibility:

- Normal nodes: source `output`; target `input`.
- `if-condition`: source `true` and source `false`; target `input`.
- UI labels are uppercase `TRUE` and `FALSE`; the IDs are lowercase `true` and `false`.

Handles are interpreted in the context of their source node type. A conditional edge's source handle is the routing decision; its array position/order has no meaning. For regular nodes, `true`/`false` are invalid; for conditional nodes, `output` is invalid for new definitions, subject to the legacy adapter below. Multiple outgoing edges from the same regular output or conditional output remain supported as fan-out.

### Backward compatibility

Preserve the workflow JSON envelope, existing node type, configuration keys, execution snapshot semantics, and all non-conditional `output` connections. Do not rewrite historical execution snapshots or introduce a database migration.

The current editor's starter graph uses `output` for one IF connection and `output-2` for another. That convention does not intrinsically prove which path was intended, but it is the only concrete legacy mapping in source. Proposed compatibility adapter, applied only to `if-condition` edges during plan construction:

- Legacy `output` maps to canonical `true`.
- Legacy `output-2` maps to canonical `false`.

The adapter must not change serialized workflow data; it normalizes the in-memory graph plan. All other unknown conditional handles remain validation errors. Add tests for both legacy handles and for rejection of `output-2` on a non-conditional node. If product owners do not accept this inferred mapping, the safer alternative is to reject legacy conditional edges with a clear migration error and require users to reconnect them.

For newly edited/saved workflows, show and persist canonical `true`/`false` handle IDs. Existing legacy nodes that lack a comparison `value` continue to load, but fail validation at execute/save time when the selected operator requires a value; do not silently invent one.

## 2. Condition evaluation and executor/runtime integration

### Keep existing executors working

The current `NodeExecutor.execute(JsonNode, ExecutionContext)` returns only a JSON output, which cannot express a selected route. Avoid breaking the existing three executors or introducing route metadata into user data. Add a small result/decision contract in the backend runtime/executor layer:

- Introduce an execution result containing the JSON output plus an optional selected output handle and optional safe result metadata.
- Preserve `NodeExecutor.execute` for existing implementations. Add a default adapter method (for example `executeResult`) that calls `execute` and wraps its output with no selected handle.
- Implement `IfConditionExecutor` as a Spring `NodeExecutor` with exact `nodeType()` `if-condition`; override the result method to return the unchanged input as output and the selected canonical route (`true` or `false`).
- Treat a non-null selected handle from a node as a routing decision. The runtime verifies it against that node's outgoing connections and activates only matching edges. Ordinary executors without route metadata activate all outgoing edges exactly as today.

This keeps executor discovery and output/data contracts compatible while allowing graph routing to consume an explicit decision.

### Evaluator behavior

Implement a pure, directly unit-testable evaluator called by `IfConditionExecutor`:

1. Validate configuration shape and supported operator before evaluating.
2. Resolve `field` against the input JSON object using safe dotted-path traversal only.
3. Apply the operator with explicit JSON-type checks and numeric semantics (`BigDecimal`/Jackson numeric comparison, not string ordering).
4. On success, return a boolean result and canonical selected branch.
5. On missing/incompatible operands or invalid configuration, raise a safe node execution error; do not choose a default branch.

The conditional node's output should be a deep copy of its input, so either selected downstream path receives the same data and existing sequential/pass-through expectations hold.

## 3. Validation and graph plan

Update the graph plan from one incoming edge per node to a stable, ordered list of incoming connections per node. Preserve the existing outgoing edge list and topological order. Preserve source/target handle IDs in each plan edge, after legacy conditional-handle normalization.

Validator changes:

- Register/recognize `if-condition` through its executor bean; unsupported-node diagnostics remain useful.
- Validate handles based on source node type as described above.
- Continue validating IDs, missing references, self-edges, duplicate edges, trigger count, cycles, and static reachability.
- Allow multiple incoming edges in the plan; this is required for rejoining. Continue to reject duplicate source/target/handle edges.
- Require at least one outgoing edge for each conditional route (`true` and `false`) in the initial milestone, because the specification calls out missing branch connections as an error. Allow multiple edges on either handle for fan-out.
- Preserve support for existing ordinary one-incoming sequential edges and multiple outgoing `output` edges.

Requiring both branch outputs is the most literal reading of the specification's missing-TRUE/FALSE-connection error case. A product decision is needed if an intentionally empty branch should instead be allowed.

## 4. Handle-aware execution and rejoining algorithm

Replace the runtime's unconditional “execute every topological node” loop with an active-edge scheduler over the already validated DAG. The validator's topological order remains the deterministic processing order; it is not used to select a branch.

Maintain a state for every edge: unresolved, active, or inactive. Process nodes in topological order:

1. The manual trigger runs as the active root and produces its output.
2. For each non-trigger node, all immediate predecessors occur earlier in topological order, so their outgoing edges have already been resolved. Collect the node's incoming active edges.
3. If all incoming edges are inactive, do not call its executor; transition its pending record to `SKIPPED`, and mark all its outgoing edges inactive. This propagates branch pruning through nested downstream paths.
4. If one or more incoming edges are active, wait until all incoming edges are resolved and all active predecessors have completed. Then execute the node exactly once. Topological processing naturally satisfies this barrier; it must not start a join when an active predecessor is still unresolved.
5. A regular executor activates every outgoing edge after successful completion, preserving existing unconditional fan-out.
6. A conditional executor activates every outgoing edge whose handle matches its selected branch and marks the opposite-handle edges inactive.
7. Once all nodes are processed, succeed the execution if no node failed. Existing failure behavior remains: record the failing node, stop further execution, mark remaining pending records `SKIPPED`, and fail the run.

### Rejoin behavior and input semantics

A join is any node with multiple incoming edges. It executes once if at least one incoming edge is active, and only after every incoming edge has resolved. Inactive incoming edges do not block a join and do not count as required predecessors; every active incoming predecessor must have completed successfully before the join starts. If all incoming edges are inactive, the join is `SKIPPED`.

For one active incoming edge, retain current behavior exactly: pass that predecessor's output as the node input and retain existing upstream context behavior. For multiple active incoming edges, do not select an arbitrary predecessor or merge objects with last-write-wins. Pass a deterministic aggregate input:

```json
{
  "inputs": {
    "source-node-id-a": { "result": "A" },
    "source-node-id-b": { "result": "B" }
  }
}
```

Keys are immediate active predecessor node IDs, serialized in stable workflow-plan order. `ExecutionContext.upstreamOutputs()` should contain deep copies of those direct active predecessor outputs for a multi-input join. This is a new, explicit input contract only for multi-active-edge nodes; one-edge sequential workflows remain byte-for-byte behaviorally compatible. Fan-out branches continue receiving independent deep copies so one branch cannot mutate another's input.

This rule handles a conditional split/rejoin correctly: one path's edge is inactive, the other path's active edge permits the shared node to run once; if the graph has additional active predecessors, the join waits for and aggregates them. Nested conditionals follow the same edge-state rules. Static cycle detection remains mandatory.

## 5. Statuses, decision logging, and monitoring

Use the existing `NodeExecutionStatus` enum and `ExecutionStateService` transitions. The execution API already creates a pending node-execution row per node; active nodes become RUNNING/SUCCEEDED or FAILED; inactive-only nodes become SKIPPED. Ensure nodes are transitioned once and that SKIPPED records are never begun later.

On successful condition evaluation, write an INFO execution log associated with the conditional node execution. Proposed structured `details`:

```json
{
  "nodeType": "if-condition",
  "field": "age",
  "operator": "greater than or equal",
  "result": true,
  "selectedBranch": "TRUE"
}
```

Do not log raw actual/comparison values by default: workflow input can contain credentials or personal data, and there is no general secret detector. If the requirement to show `actualValue`/`comparisonValue` is retained, add a deliberate redaction policy and tests before including them. The log message can say which branch was selected without including operand values.

The existing execution-details page already renders node statuses, outputs, errors, and structured log details. Update its status styling so `SKIPPED` is visually distinct from `FAILED` (for example neutral/slate styling versus red) and make the selected branch visible through the conditional log. No new API endpoint or database column is required.

## 6. Affected files

### Backend implementation

- `backend/src/main/java/com/flowforge/executor/NodeExecutor.java` — backward-compatible execution-result adapter.
- `backend/src/main/java/com/flowforge/executor/IfConditionExecutor.java` — registered `if-condition` executor and decision result.
- `backend/src/main/java/com/flowforge/executor/ConditionEvaluator.java` (or equivalent focused runtime helper) — typed comparison and safe field path resolution.
- `backend/src/main/java/com/flowforge/runtime/WorkflowGraphPlan.java` — list-valued incoming edges and normalized handle-aware graph representation.
- `backend/src/main/java/com/flowforge/validation/WorkflowGraphValidator.java` — supported condition handles, both-route validation, multi-incoming support, legacy adapter.
- `backend/src/main/java/com/flowforge/runtime/WorkflowExecutionRuntime.java` — active-edge scheduler, branch pruning, join barrier/input aggregation, conditional logs.
- `backend/src/main/java/com/flowforge/service/ExecutionStateService.java` — only if the existing state API needs a narrow operation for marking branch-skipped nodes; current `skipNode` may be reusable unchanged.

No entity, repository, API DTO, database migration, or Spring application bootstrap change is planned unless implementation inspection uncovers a concrete need.

### Frontend

- `src/app/core/models/workflow-editor.model.ts` — typed conditional operators/scalar value and handle identifier constants/types while retaining the existing connection shape.
- `src/app/features/workflows/workflow-editor/workflow-editor.ts` — render `true`/`false` outputs only on IF nodes; preserve generic output on other nodes; operator/configuration controls, scalar input type, condition summary, handle-specific connection creation/path anchors, loading/saving normalization.
- `src/app/features/executions/execution-details.ts` — distinguish SKIPPED visually and present decision log detail; reuse existing polling/API structures.
- `src/app/features/workflows/workflow-editor/workflow-editor.spec.ts` — editor and persistence tests.

### Tests and documentation

- `backend/src/test/java/com/flowforge/validation/WorkflowGraphValidatorTest.java`
- New evaluator unit test, e.g. `backend/src/test/java/com/flowforge/executor/ConditionEvaluatorTest.java`
- `backend/src/test/java/com/flowforge/controller/ExecutionApiIntegrationTest.java`
- `backend/src/test/java/com/flowforge/runtime/WorkflowExecutionWorkerTest.java` only if needed for conditional runtime failures/statuses.
- `src/app/features/workflows/workflow-editor/workflow-editor.spec.ts`
- Add focused execution-details test if the component's test structure supports it.
- Create `docs/milestones/05-conditional-branching.md`; update `docs/ROADMAP.md`, `docs/runtime/NODE_SYSTEM.md`, `docs/runtime/EXECUTION_ENGINE.md`, and `docs/runtime/DATA_FLOW.md`. These files/directories were not present in the inspected `docs` listing, so create the required directories/files carefully without replacing the existing instructions.

## 7. Proposed test plan

### Evaluator unit tests

- String equals/not-equals, including type mismatch.
- Numeric greater-than, greater-than-or-equal, less-than, less-than-or-equal using numeric JSON values (including decimal values; ensure no lexical comparison).
- Boolean equality/not-equality.
- Dotted object path resolution.
- `is empty`/`is not empty` for missing, null, empty string, and non-empty values.
- Missing/null field errors for ordinary comparisons.
- Missing config, blank field, unsupported operator, absent required comparison value, incompatible types, arrays/objects, and invalid ordering comparisons.
- Confirm exception messages contain useful node/config context but not operand secrets.

### Validator tests

- Accept `true` and `false` only for `if-condition`; accept `output` for ordinary nodes.
- Reject branch handles on non-conditional nodes and invalid target handles.
- Require both conditional branches; accept multiple outgoing edges per branch.
- Accept and normalize the documented legacy `output`/`output-2` conditional pair; reject other legacy/unknown IDs and reject `output-2` on regular nodes.
- Allow multiple incoming edges; continue to reject duplicate connections, cycles, missing references, self-links, and unreachable nodes.
- Preserve existing sequential and unconditional-fan-out validation expectations.

### Backend integration tests

- TRUE and FALSE workflows each execute only their selected branch; other branch nodes become SKIPPED and execution succeeds.
- Validate status/output/input/log persistence and verify the IF node passes input data unchanged.
- Test selected-branch multi-node fan-out with isolated data copies.
- Test a branch that skips one incoming edge to a shared join: the join executes exactly once after the active predecessor completes.
- Test a join with two active incoming edges: it waits for both and receives the specified deterministic aggregate input.
- Test all incoming edges inactive: join becomes SKIPPED and does not execute.
- Test nested branches/rejoins, node failure before/after a decision, missing branch outputs, and condition errors.
- Re-run existing sequential, fan-out, data passing, failure, and asynchronous execution tests without altering their expected behavior.
- Confirm immutable workflow snapshots preserve their original handles/configuration and use the runtime-only compatibility normalization.

### Frontend tests

- Add IF node; verify its two labeled output handles use IDs `true` and `false`, while normal nodes retain `output`.
- Connect TRUE and FALSE independently and verify saved connection records retain the corresponding source handles.
- Edit field/operator/scalar value; save/reload preserves values and type.
- Verify legacy `output`/`output-2` conditional connections load and are converted only according to the agreed compatibility policy.
- Move nodes and verify branch connections remain attached to their original handles.
- Verify skipped status appears distinct from failed, and selected branch is visible in execution details.

### Verification sequence

Run focused evaluator/validator/runtime backend tests, then `cd backend && ./gradlew clean test` and `./gradlew build`. Run focused Angular specs, then the frontend test suite and production build. Report exact commands and results; do not mark the milestone complete from compilation alone. No tests/builds have been run while preparing this plan.

## 8. Decisions and risks to resolve before implementation

1. **Legacy `output`/`output-2` mapping:** Proposed `output` → TRUE and `output-2` → FALSE follows the editor's seeded graph but is an inferred semantic mapping. Confirm it, or reject legacy conditional connections for explicit user repair.
2. **Required branch connections:** Proposed validator requires both TRUE and FALSE to have at least one outgoing edge. Confirm whether an empty/no-op branch should be legal.
3. **Join data contract:** Proposed aggregate input for multiple active predecessors is explicit and avoids arbitrary selection, but it is new behavior for graphs currently rejected by validation. Confirm consumers can use `input.inputs.<nodeId>` or adjust to a formally documented merge node in a later milestone.
4. **Typed comparison UI:** The current text input persists all values as strings. Add scalar-type selection so numeric/boolean comparisons are unambiguous; existing saved strings remain strings and are never silently coerced.
5. **Sensitive decision data:** The specification's example includes actual/comparison values. Proposed logs omit them by default to avoid storing workflow secrets/PII; confirm that this satisfies monitoring needs or define a redaction/opt-in policy.
6. **Static unreachable validation:** Existing validation rejects graph nodes that are statically unreachable from the trigger. Keep that invariant; conditional pruning is runtime state, not static unreachable structure.


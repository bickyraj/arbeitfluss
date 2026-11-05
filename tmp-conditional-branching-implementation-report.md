# Milestone 9.3 Conditional Branching — Implementation Report

## Result

Conditional branching is implemented in the existing Angular editor and single Spring Boot backend. Canonical TRUE/FALSE connections, typed condition configuration, safe condition evaluation, active-branch execution, branch skipping, fan-out, joins, nested conditions, decision logs, and execution-detail status presentation are in place.

**Milestone status: NOT MARKED COMPLETED.** Backend/frontend suites and production builds passed. A browser-driven end-to-end test that runs age 20 and age 15 through the UI against the live backend has not been performed.

## Implemented features

- Kept node type `if-condition` and the existing node/connection JSON schema.
- Added canonical `true`/`false` source handles and labels for editor-created IF/ELSE nodes; ordinary nodes retain `output`.
- Added the full specified operator set and Text/Number/Boolean/Null comparison-value UI. Existing string values remain strings; no silent type coercion occurs.
- Added editor-side configuration validation on save/run and backend executor configuration validation before execution creation.
- Registered Spring `IfConditionExecutor` for exact type `if-condition`; added typed evaluator with safe dotted object field lookup, numeric semantics, explicit type errors, and no expression evaluation.
- Added a backward-compatible `NodeExecutionResult` adapter; the existing executor `execute` API remains supported.
- Preserved legacy conditional source handles at runtime-plan construction only: `if-condition` `output` -> TRUE, `output-2` -> FALSE. Serialized definitions and immutable snapshots remain unchanged.
- Added edge-aware topological scheduling; normal-node fan-out remains unconditional. Conditional nodes activate only the chosen handle.
- Pending nodes with only inactive incoming edges are marked `SKIPPED`, and their outgoing edges become inactive. Nested inactive subgraphs are skipped.
- Enabled multi-incoming joins. A join executes once if at least one incoming edge is active and only after all predecessors have resolved; if every incoming edge is inactive, it is skipped.
- Defined multi-active join input as `{ "inputs": { "<immediate-predecessor-id>": <output> } }` in topological predecessor order. Single-active input retains prior behavior.
- Added decision INFO logs containing node type, field, operator, result, and selected branch; logs omit actual/comparison values. Execution details distinguish skipped nodes from failed nodes and show the decision log.
- Updated IF/ELSE template connections to canonical handle IDs.
- No execution API or database schema/migration changes.

## Files changed

**Frontend**

- `src/app/core/models/workflow-editor.model.ts`
- `src/app/features/workflows/workflow-editor/workflow-editor.ts`
- `src/app/features/workflows/workflow-editor/workflow-editor.spec.ts`
- `src/app/features/templates/templates-page.ts`
- `src/app/features/executions/execution-details.ts`
- `src/app/features/executions/execution-details.spec.ts`

**Backend**

- `backend/src/main/java/com/flowforge/executor/NodeExecutor.java`
- `backend/src/main/java/com/flowforge/executor/NodeExecutionResult.java` (new)
- `backend/src/main/java/com/flowforge/executor/ConditionEvaluator.java` (new)
- `backend/src/main/java/com/flowforge/executor/IfConditionExecutor.java` (new)
- `backend/src/main/java/com/flowforge/runtime/WorkflowGraphPlan.java`
- `backend/src/main/java/com/flowforge/runtime/WorkflowExecutionRuntime.java`
- `backend/src/main/java/com/flowforge/validation/WorkflowGraphValidator.java`
- `backend/src/test/java/com/flowforge/executor/ConditionEvaluatorTest.java` (new)
- `backend/src/test/java/com/flowforge/validation/WorkflowGraphValidatorTest.java`
- `backend/src/test/java/com/flowforge/controller/ExecutionApiIntegrationTest.java`
- `backend/src/test/java/com/flowforge/controller/ExecutionDispatchIntegrationTest.java`

**Documentation**

- `docs/milestones/05-conditional-branching.md` (new)
- `docs/ROADMAP.md` (new; no prior roadmap existed)
- `docs/runtime/NODE_SYSTEM.md` (new)
- `docs/runtime/EXECUTION_ENGINE.md` (new)
- `docs/runtime/DATA_FLOW.md` (new)
- `tmp-conditional-branching-implementation-report.md` (this report)

## Important architectural decisions

- No new Spring Boot application, queue service, database migration, or dependency was added.
- The runtime normalizes legacy handles in memory and does not mutate saved workflow snapshots.
- Both conditional outputs are required and validated before execution is queued.
- A join with multiple active predecessors receives deterministic, explicit aggregate input rather than arbitrary selection or object merging.
- Decision logs omit potentially sensitive actual and comparison values.
- Existing node executor implementations continue using `execute`; the default result adapter adds route metadata without changing their contract.
- `is empty`/`is not empty` treat missing/null/empty-string values as empty. Explicit null equals explicit null for equality. Arrays/objects and incompatible scalar comparisons are rejected.

## Test commands and results

| Command | Actual result |
|---|---|
| `cd backend && ./gradlew clean test` | Passed — 21 tests |
| `cd backend && ./gradlew build` | Passed |
| `npm test -- --watch=false` | Passed — 15 tests across 5 files |
| `npm run build` | Passed |
| `git diff --check` | Passed |

Earlier full-suite attempts exposed test-only issues: an outdated validator assertion after configuration validation was added, a dispatch test racing to observe QUEUED in the database even though the API response had already been queued, and a component test reading its polling result before the first timer tick. Those expectations were corrected; the final backend clean test/build and frontend test/build commands above passed.

Focused backend coverage includes every supported operator, missing/incompatible values, validation, canonical and legacy handles, TRUE/FALSE routing, conditional fan-out, selected/unselected status persistence, active and all-inactive joins, exactly-once shared downstream execution, nested conditions, data passing, log contents, and immutable snapshots. Existing sequential, unconditional fan-out, failure, and asynchronous dispatch tests also passed.

Frontend coverage includes adding IF/ELSE, canonical handle IDs, typed configuration persistence, legacy string preservation, invalid field validation, conditional connection persistence, and skipped/decision presentation.

## Known limitations and unfinished verification

- No full browser-driven UI-to-live-backend acceptance run was performed; therefore the milestone is not labeled COMPLETED.
- Field paths support object properties only; arrays and arbitrary expressions are unsupported.
- Text ordering is lexical.
- HTTP Request, Send Email, Schedule Trigger, Webhook Trigger, and other non-registered frontend node types remain outside executable backend scope.
- Actual/comparison values are intentionally absent from decision logs.

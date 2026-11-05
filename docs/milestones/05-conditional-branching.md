# Milestone 9.3 — Conditional Branching

## Status

Conditional branching is implemented in the existing Angular editor and Spring Boot execution runtime. Backend tests, frontend tests, and both production builds passed. Milestone 9.3 is **not marked COMPLETED** because the end-to-end acceptance workflow has not yet been exercised in a browser against a running frontend and backend.

## Saved workflow contract

The existing workflow JSON structure is retained. The node type is `if-condition`; condition settings are stored in the node's existing `configuration` object:

```json
{
  "id": "age-check",
  "type": "if-condition",
  "name": "IF / ELSE",
  "description": "Branches when a condition is true.",
  "position": { "x": 420, "y": 170 },
  "configuration": {
    "field": "age",
    "operator": "greater than or equal",
    "value": 18
  }
}
```

`field` supports dot-separated object paths, `operator` supports `equals`, `not equals`, `greater than`, `greater than or equal`, `less than`, `less than or equal`, `is empty`, and `is not empty`. Comparison values remain JSON scalars; the editor has explicit Text, Number, Boolean, and Null types and preserves existing string values. Strings are never silently converted to numbers or booleans. Ordering accepts numbers or strings; boolean ordering and incompatible scalar types fail explicitly.

Connection records retain the existing fields. Normal nodes use source handle `output`; conditional nodes use canonical source handles `true` and `false`; all target handles remain `input`. The editor shows TRUE/FALSE labels. Each conditional node must have at least one outgoing connection on both handles. Conditional fan-out is supported on either handle.

For legacy saved `if-condition` nodes only, graph-plan construction maps source handle `output` to `true` and `output-2` to `false`. The stored workflow and immutable execution snapshot are not rewritten. Those legacy identifiers are not accepted as handles on non-conditional nodes. New editor-created connections save canonical IDs.

## Evaluation and execution

`IfConditionExecutor` is registered under the exact type `if-condition`. `ConditionEvaluator` traverses object fields without expression evaluation. Equality requires compatible scalar types; numbers use numeric comparison, strings use lexical comparison, and booleans support equality only. Explicit null equals explicit null for equality; a missing field is an error except with `is empty`/`is not empty`. Those operators treat missing, null, and an empty string as empty. Objects and arrays are invalid operands.

The conditional executor passes its input through unchanged and returns an explicit selected output handle. Existing executors retain their original `execute` contract through a default result adapter. The runtime processes the validated DAG in topological order, activates all outgoing edges for normal nodes, and activates only conditional edges matching the evaluator's decision. Edge-array order does not select a branch.

Every incoming edge has an active/inactive resolution before a node is reached. A node with no active incoming edges is not executed, becomes `SKIPPED`, and deactivates its outgoing edges. This propagates through nested unselected paths. A node with at least one active input executes once, after all predecessors have been processed. Thus an inactive edge does not block a join, and a join whose inputs are all inactive is skipped.

For a join with exactly one active incoming edge, the existing input behavior is preserved. With multiple active incoming edges, input is aggregated in topological source-node order:

```json
{
  "inputs": {
    "source-node-a": { "age": 20, "branch_a": 1 },
    "source-node-b": { "age": 20, "branch_b": 2 }
  }
}
```

The `ExecutionContext.upstreamOutputs()` for a multi-input join contains deep copies of those direct active predecessor outputs. The join runs once; it is not scheduled once per edge. Each fan-out executor receives an isolated JSON copy.

Successful decisions write an INFO execution log containing `nodeType`, `field`, `operator`, boolean `result`, and uppercase `selectedBranch`. Actual and comparison values are intentionally omitted. Execution details show the decision log and distinguish `SKIPPED` from `FAILED`. Existing API DTOs, persistence entities, migrations, and execution endpoints are unchanged.

## Example graph

```json
{
  "nodes": [
    { "id": "trigger", "type": "manual-trigger", "configuration": {} },
    { "id": "age-check", "type": "if-condition", "configuration": {
      "field": "age", "operator": "greater than or equal", "value": 18
    } },
    { "id": "adult", "type": "log-message", "configuration": { "message": "Access granted" } },
    { "id": "minor", "type": "log-message", "configuration": { "message": "Access denied" } }
  ],
  "connections": [
    { "id": "start", "sourceNodeId": "trigger", "sourceHandle": "output", "targetNodeId": "age-check", "targetHandle": "input" },
    { "id": "yes", "sourceNodeId": "age-check", "sourceHandle": "true", "targetNodeId": "adult", "targetHandle": "input" },
    { "id": "no", "sourceNodeId": "age-check", "sourceHandle": "false", "targetNodeId": "minor", "targetHandle": "input" }
  ]
}
```

With trigger input `{"age":20}`, `adult` executes and `minor` is `SKIPPED`. With `{"age":15}`, the opposite branch executes. Conditional runtime tests exercise these branches through the execution API.

## Verification

| Command | Result |
|---|---|
| `cd backend && ./gradlew clean test` | Passed — 21 tests |
| `cd backend && ./gradlew build` | Passed |
| `npm test -- --watch=false` | Passed — 15 tests across 5 files |
| `npm run build` | Passed |

Coverage includes all supported operators, missing/incompatible values, canonical and legacy handles, TRUE/FALSE execution, branch fan-out, skipped paths, nested conditions, active and all-inactive joins, single execution of shared joins, data isolation/passing, existing sequential workflows, unconditional fan-out, execution failure handling, async dispatch, editor configuration/persistence, and execution-detail presentation.

## Limitations

- The full age-20/age-15 flow has been verified through backend execution API integration tests; a browser-driven UI-to-live-backend end-to-end run remains outstanding.
- Dotted field paths traverse JSON objects only; array indexing and arbitrary expressions are not supported.
- String ordering is lexical. No locale-aware comparison is provided.
- `is empty` and `is not empty` accept scalar values and treat missing/null/empty string as empty; arrays and objects are rejected.
- Other editor node types such as HTTP Request, Send Email, Webhook Trigger, and Schedule Trigger are outside this milestone's backend executor support.
- Decision logs omit actual and comparison values by design to avoid persisting potentially sensitive workflow data.

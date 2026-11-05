# Workflow Execution Data Flow

## Connected-node input

For one active incoming edge, the node receives a deep copy of its immediate predecessor's output, preserving existing linear execution behavior. The trigger receives the execution request's `triggerInput`. Executors receive their configuration and shared execution context via `ExecutionContext`.

`if-condition` inspects its input using a safe dot-separated JSON object path and returns the input unchanged. It produces route metadata separately from user data. The selected TRUE/FALSE output determines which outgoing connections become active.

## Fan-out and joins

Each fan-out receives an isolated deep copy; branch mutations do not leak to sibling branches. A multi-input node waits until all incoming edges are resolved and all active predecessors have completed. It executes once if at least one input is active.

For multiple active inputs the JSON contract is:

```json
{
  "inputs": {
    "upstream-node-id": { "data": "value" }
  }
}
```

The `inputs` properties are immediate active predecessor node IDs in topological source-node order. The execution context's `upstreamOutputs()` contains the corresponding deep-copied direct outputs. If all incoming edges are inactive, the node is marked SKIPPED instead of executing.

## Comparison types

Numbers compare numerically. Strings compare for equality and lexical ordering only against strings. Booleans support equality and not-equality only. Explicit null can be compared to explicit null for equality; missing fields fail except for `is empty` and `is not empty`, which treat missing, null, and an empty string as empty. Objects, arrays, implicit scalar coercion, and arbitrary expressions are not supported.

Decision logs include the field/operator/result/selected branch but omit operand values. Node inputs/outputs continue to use the existing execution API and persistence contracts.

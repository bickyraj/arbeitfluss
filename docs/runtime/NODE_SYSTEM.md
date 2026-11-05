# Runtime Node System

## Node identity and registration

Workflow definitions store nodes using the existing JSON shape: `id`, `type`, optional display fields/position, and `configuration`. Backend `NodeExecutor` Spring beans register by the exact string returned from `nodeType()`. `NodeExecutorRegistry` rejects duplicate registrations and is used by graph validation and execution.

Current executable types are `manual-trigger`, `set-data`, `log-message`, and `if-condition`. Other types in the editor are not thereby executable.

Existing executors implement `execute(JsonNode, ExecutionContext)`. The default `executeResult` adapter wraps their output and activates all normal outgoing connections. Executors that route conditionally return a `NodeExecutionResult` with an explicit selected output handle; user payload data is not overloaded with routing metadata.

## IF/ELSE node

The registered type is exactly `if-condition`. Its configuration is:

```json
{
  "field": "user.age",
  "operator": "greater than or equal",
  "value": 18
}
```

Supported operators: equals, not equals, greater than, greater than or equal, less than, less than or equal, is empty, and is not empty. Values are JSON scalars and retain their types. Numeric comparisons are numeric, text ordering is lexical, and incompatible types fail. Field lookup is limited to dot-separated object paths.

Conditional connection source handles are `true` and `false` (labels TRUE/FALSE in the editor). The node must connect at least one edge from each handle. Ordinary nodes use `output`. Target handle is `input`.

Legacy conditional connections are normalized in the runtime graph plan only: `if-condition` + `output` means `true`; `if-condition` + `output-2` means `false`. This compatibility alias is not accepted for ordinary node types and does not rewrite saved workflows or immutable snapshots.

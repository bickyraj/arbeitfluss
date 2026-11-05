# Milestone 9.5 — HTTP Request Architecture Inspection

## Inspection scope

This report was created from a read-only architecture inspection before source changes. The implementation outcome and verification results are recorded in Section 8.

## 1. Relevant frontend files

- `src/app/core/models/workflow-editor.model.ts`
  - Declares the `WorkflowNodeType` union, editor node model and configuration model. `http-request` is already in the node type union. The configuration is currently scalar-only (`Record<string, WorkflowScalarValue | undefined>`), while this node needs structured query/header lists and a JSON body.
- `src/app/features/workflows/workflow-editor/workflow-editor.ts`
  - Owns the node library, node defaults, icon mapping, rendering, configuration panel, editor state and persistence. `http-request` is already listed under “Actions” and rendered in the panel, but its current controls only cover GET/POST/PUT/DELETE plus URL, raw headers and body strings. It lacks PATCH, response format, timeout, redirect policy, structured query/header editing, validation, and body type.
  - Nodes are serialized through the existing workflow definition API; configuration is stored in each node's `configuration` object.
- `src/app/features/executions/execution-details.ts`
  - Displays persisted node input/output JSON and logs. It currently renders full node output without response-size-aware presentation; the HTTP executor must therefore enforce a compact bounded output and exclude secrets before persistence.
- `src/app/features/workflows/workflow-editor/workflow-editor.spec.ts`
  - Existing editor tests cover workflow editing, execution dialog and conditional branching, but no HTTP node configuration.

## 2. Relevant backend files

- `backend/src/main/java/com/flowforge/executor/NodeExecutor.java`
  - Executor contract: `nodeType()`, optional `validateConfiguration(JsonNode)`, `execute(JsonNode input, ExecutionContext)`, and default `executeResult(...)`.
- `backend/src/main/java/com/flowforge/executor/NodeExecutorRegistry.java`
  - Spring injects all `NodeExecutor` beans and indexes them by exact node type. `WorkflowGraphValidator` rejects any node type without a registered executor and calls its configuration validator.
- Existing executors (`ManualTriggerExecutor`, `SetDataExecutor`, `LogMessageExecutor`, `IfConditionExecutor`) establish behavior and error/log conventions.
- `backend/src/main/java/com/flowforge/validation/WorkflowGraphValidator.java`
  - Validates executor support/configuration and preserves the existing graph plan and edge validation. Registering `HttpRequestExecutor` plus its config validator makes the exact `http-request` type accepted without graph/runtime special cases.
- `backend/src/main/java/com/flowforge/runtime/ExecutionContext.java`
  - Provides immutable node configuration, input ancestry, execution/node IDs, execution deadline, logging, and expiry checking. It now also exposes cooperative cancellation and active-operation cancellation registration for HTTP calls.
- `backend/src/main/java/com/flowforge/runtime/WorkflowExecutionRuntime.java`
  - Executes nodes within the existing graph scheduler, persists node input/output through `ExecutionStateService`, enforces the Milestone 9.4 deadline by running each executor in an interruptible virtual thread and cancelling at deadline, and preserves fan-out, branch joins and downstream data passing. HTTP calls are polled for cancellation and can abort the active request.
- `backend/src/main/java/com/flowforge/service/ExecutionStateService.java`
  - Persists node RUNNING/SUCCEEDED/FAILED/SKIPPED states and execution logs. Runtime persists executor output as node output; downstream nodes receive that JSON as their input. A narrow `failNode` overload now also preserves safe structured HTTP error output on a failed node.
- `backend/src/main/java/com/flowforge/entity/NodeExecution.java` and `dto/execution/NodeExecutionResponse.java`
  - Store and return JSON input/output, timestamps, duration and safe error message. There is no separate per-node HTTP response schema.
- `backend/src/main/resources/application.yml`
  - Existing configuration does not define HTTP response byte limits; choose a centralized property/default and enforce it while streaming.
- `backend/build.gradle.kts`
  - Uses Apache HttpClient 5.4.2 for bounded outbound HTTP, with a custom DNS resolver and automatic redirects/retries/cookies disabled.
- Existing backend tests use Spring Boot integration tests and an H2 test profile. The integration test suite has a custom blocking executor for cancellation and timeout behavior; no local HTTP mock server dependency currently exists.

## 3. Existing contracts and how HTTP integrates

Workflow definitions are JSON containing `nodes[]`; each node contains its exact `type`, `name`, and arbitrary `configuration` JSON. Workflow CRUD preserves the JSON definition without a database migration. A node added in the editor is persisted in that same configuration object.

At execution, the graph validator looks up the type in `NodeExecutorRegistry`, validates configuration, and stores the node type on a `NodeExecution`. The runtime passes the prior active predecessor's output as the next executor's `input`; the executor's returned `JsonNode` is saved as `output_data`, exposed through execution details, and passed unchanged to connected downstream nodes. Therefore `HttpRequestExecutor` can produce the specified `{statusCode, headers, body, success}` contract without changing the execution API, persistence schema, or graph scheduler.

`http-request` is already declared in the frontend registry/model, but it is not registered in the backend executor registry and existing sample defaults use an older incompatible configuration (`headers`/`body` strings). This explains why existing editor-created HTTP nodes cannot currently execute.

No reliable expression interpolation mechanism was found in the inspected executors/runtime. The compatible initial behavior is to send the configured JSON body as-is and not invent template syntax.

## 4. Security risks and protections required

An arbitrary URL turns the backend into an SSRF-capable proxy. Hostname checks alone are insufficient. The implementation must:

1. Parse only absolute `http`/`https` URLs; reject user-info, malformed/ambiguous hosts, unsupported schemes, and nonstandard ports unless the product chooses otherwise.
2. Resolve every destination at connection time and reject any loopback, private, link-local, multicast, unspecified, reserved, metadata, or otherwise non-public address. Pin/validate the resolver result used by the actual HTTP connection to mitigate DNS rebinding.
3. Disable automatic redirects and, when configured to follow redirects, process each hop manually through the same URL + DNS/IP policy. Apply a small redirect cap. Revalidate each hop and reject HTTPS-to-HTTP downgrade.
4. Build query parameters through a URI builder with correct escaping.
5. Bound connect/request/read durations by the lower of configured request timeout and remaining workflow deadline. Expose a cancellation signal to the runtime executor and abort the active HTTP call on interruption where supported.
6. Stream response bytes with a central maximum (initially 5 MiB), abort before reading beyond the cap, and parse JSON only for JSON content/selected format.
7. Never log request/response bodies, header values, credentials, or raw exception text. Return safe response headers only; remove authorization, cookie, set-cookie, proxy-auth, and other sensitive headers before node output persistence. Map failures to stable safe error codes/messages.

Apache HttpClient's custom DNS resolver validates and returns the address set used by the connector itself; a separate preflight validation also provides a safe, explicit error before the connection is attempted. Automatic redirects are disabled and redirects are followed manually.

Arbitrary user-provided headers can contain secrets and are persisted in the workflow JSON. Milestone 9.5 has no credentials vault. The HTTP node panel should warn that header values are stored with the workflow, and execution logs/details must not echo them. Restrict sensitive response/request header exposure regardless of what the workflow editor can read.

## 5. Proposed configuration and output contracts

Use the existing node type `http-request`. Persist structured node configuration in the existing workflow JSON:

```json
{
  "method": "GET",
  "url": "https://api.example.test/users",
  "responseFormat": "auto",
  "timeoutSeconds": 30,
  "followRedirects": false,
  "queryParameters": [
    { "name": "page", "value": "1", "enabled": true }
  ],
  "headers": [
    { "name": "Accept", "value": "application/json", "enabled": true }
  ],
  "bodyType": "none",
  "body": null
}
```

Allowed methods: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. Formats: `auto`, `json`, `text`. Body types: `none`, `json`; JSON bodies only for POST/PUT/PATCH. Omitted timeout uses a documented bounded default; when present it must be a positive value within a central cap. Node request timeout is always clipped to the remaining execution deadline.

Successful HTTP response output:

```json
{
  "statusCode": 200,
  "headers": { "content-type": "application/json" },
  "responseFormat": "JSON",
  "body": { "userId": 123 },
  "success": true
}
```

For a non-2xx response, preserve status, safe headers, and bounded parsed/text body as node output, but throw a safe `NodeExecutionException` so the existing runtime marks the node and execution FAILED and does not schedule successors. This uses the existing JSON output column and adds no schema migration. Safe node error text must not contain response data or request secrets.

## 6. Implementation plan

1. **Contracts and security transport:** choose/add an HTTP library that supports a custom validated DNS resolver, pinned lookup per actual connection, disabled automatic redirects, and cancellable request execution. Define URL/IP policy, timeout/redirect/response-size limits, safe-header allowlist, and stable error codes.
2. **Backend executor:** implement strict configuration validation, URI/query building, JSON body serialization, manual redirect processing with per-hop validation, streaming response-size limits, JSON/text/auto parsing, status output, and safe failure mapping.
3. **Runtime cancellation integration:** extend `ExecutionContext` with a read-only cancellation predicate and make the worker's executor wait respond to cancellation by interrupting the active node task. Keep cancellation cooperative and preserve the current `CANCELLING` lifecycle and node-state semantics. Test an in-flight local HTTP request is aborted on cancellation and terminal state is CANCELLED.
4. **Frontend configuration:** update configuration typing to support structured JSON values, replace the legacy HTTP string fields with method/URL/format/timeout/redirect/query/header/body controls, validate required URL/method/body/JSON/timeout locally, and warn that custom header values are saved with the workflow.
5. **Result inspection:** render status, response body, filtered headers and error safely in existing node execution details; constrain response bytes in backend and use bounded/collapsible output presentation.
6. **Tests/docs:** use a deterministic local mock server for request behavior and frontend tests for defaults/configuration; add SSRF policy tests without public internet; regression-test existing sequential, branching, joins, retry, cancellation and data passing; update runtime docs and implementation report.

## 7. Decisions resolved during implementation

- **Outbound HTTP client:** Apache HttpClient 5.4.2, with a custom resolver that checks and returns the validated address set used for the connection. Automatic redirects, retries, and cookie management are disabled.
- **Non-2xx visibility:** keep the existing output-data schema and preserve bounded status, safe headers, format and body JSON on the FAILED node record via a narrow state-service overload.
- **Custom header secrets:** values remain stored with workflow configuration because credentials management is out of scope. The editor warns users; request headers/bodies and response bodies are not written to logs, response headers are allowlisted, and sensitive credential-like headers are removed after cross-origin redirects.
- **Defaults:** GET, Auto, redirects disabled, no body, 30-second request timeout, 5 MiB streamed response cap. The request timeout is clipped to the remaining per-execution deadline.
- **Interpolation:** no compatible interpolation engine exists; JSON request bodies are sent literally. No custom template syntax was introduced.

## 8. Implementation outcome and verification

### Implemented

- Registered `HttpRequestExecutor` through the existing Spring executor registry. It validates configuration and supports GET, POST, PUT, PATCH, DELETE; structured query parameters and headers; JSON request bodies; Auto/JSON/Text response handling; and the documented response output.
- Reused the workflow graph/runtime, node persistence and execution details API. A downstream Set Data node receives the HTTP output through the existing executor input contract. HTTP 4xx/5xx and invalid forced-JSON responses fail the node/execution, preserve bounded response details on the failed node, and stop downstream scheduling.
- Added Apache HttpClient with manual redirect handling (maximum five), public-address DNS resolution for the actual connector, a one-second DNS lookup bound, a five-megabyte streamed response limit, bounded connect/response/request timeouts, disabled retries/cookies, and cancellation of an active request.
- Added an SSRF policy rejecting non-HTTP(S) URLs, embedded credentials, non-public/private/reserved destination addresses, and unsafe redirect targets; it blocks HTTPS-to-HTTP redirects and strips credential-like headers on cross-origin redirects.
- Safe logs contain only status and a stable error code/message. Response headers are allowlisted; no request headers or bodies are logged. Execution details show node status, HTTP status, response format, duration, safe headers/body, error, and a 30,000-character output preview cap.
- Updated the Angular node model, default configuration and configuration panel with method, URL, response format, timeout, redirect control, editable query/header rows, enabled toggles, JSON body editor and validation. The editor cautions that header values are stored with the workflow.
- Added `docs/runtime/HTTP_REQUEST_NODE.md` and linked it from the execution engine documentation.

### API, persistence and architecture

- No new REST endpoint, second application, execution engine, database entity or migration was required. The existing workflow JSON `configuration` is the node configuration contract; existing execution/node endpoints expose HTTP results.
- Failure output is persisted through the existing `NodeExecution.output_data` JSON field. No database migration was created.
- Existing files already contained uncommitted Milestone 9.4 work before this milestone. Those changes were preserved; this implementation only extended the executor/runtime cancellation and failure-output paths needed for HTTP.

### Files created or modified for Milestone 9.5

- Backend: `backend/build.gradle.kts`; `executor/HttpRequestExecutor.java`; `executor/OutboundAddressPolicy.java`; `executor/SSRFBlockedException.java`; updates to `executor/NodeExecutor.java`, `executor/NodeExecutionException.java`, `runtime/ExecutionContext.java`, `runtime/WorkflowExecutionRuntime.java`, and `service/ExecutionStateService.java`.
- Backend tests: `backend/src/test/java/com/flowforge/executor/HttpRequestExecutorTest.java` and `OutboundAddressPolicyTest.java`.
- Frontend: `src/app/core/models/workflow-editor.model.ts`; `src/app/features/workflows/workflow-editor/workflow-editor.ts` and `.spec.ts`; `src/app/features/executions/execution-details.ts` and `.spec.ts`.
- Documentation/report: `docs/runtime/HTTP_REQUEST_NODE.md`; `docs/runtime/EXECUTION_ENGINE.md`; `tmp-http-request-implementation-report.md`.

### Test/build results

- `cd backend && ./gradlew test build` — passed (48 tests), including existing workflow execution/branching/cancellation/recovery tests and new deterministic local-server HTTP tests.
- `npm test -- --watch=false` — passed (24 tests across 6 test files).
- `npm run build` — passed (Angular production build).
- `git diff --check` — passed.
- HTTP tests cover JSON GET/Auto parsing, encoded query parameters, custom headers, POST JSON, text responses, 4xx/5xx failures, downstream data passing, connection/DNS failures, request timeout, cancellation of an active request, malformed forced-JSON responses, SSRF/private-address rejection, DNS rebinding, blocked redirects, cross-origin credential-header stripping, response-size limits, safe header/log handling, and public/reserved address classification.
- The mock server is local and deterministic. A test-only injected resolver permits its loopback destination; production has no such exception.

### Known limitations and outstanding manual verification

- No browser-based full workflow acceptance or deployed PostgreSQL/runtime test was performed. Automated backend tests use the project's H2 test profile. The milestone should not be declared fully accepted until a manual workflow is run in a configured environment.
- TLS certificate failure mapping is implemented with safe messages, but a deterministic HTTPS certificate-failure test was not added. The local mock suite also tests executor-to-downstream data passing directly rather than running a complete HTTP workflow through a deployed application.
- Manually verify Manual Trigger → HTTP Request → Set Data/Log Message for a permitted public HTTPS JSON endpoint and a POST JSON endpoint; inspect the saved node output and downstream input in execution details.
- In a controlled environment, manually verify text/HTML, 4xx/5xx, a slow endpoint, cancel during a request, redirects (including a blocked private target), and oversized responses. Do not use real credentials in the test workflow.
- Credentials management is absent; custom header values remain readable in the workflow definition/API to users with workflow access. Exactly-once external side effects are not guaranteed, and cancellation/retry cannot undo remote effects.
- The 5 MiB response cap and 30-second default request timeout are centralized constants rather than operator-configurable settings. Request expressions/interpolation, OAuth/credentials, advanced extraction, and automatic retries are not implemented.

# FlowForge Trigger Mismatch Root-Cause Report

## Summary

The browser is still reporting:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Execution trigger type does not match the workflow's root trigger",
  "path": "/api/workflows/d7b865dd-5ed8-4614-b193-203b02576a26/executions",
  "timestamp": "2026-11-05T19:28:31.080776Z"
}
```

The source and compiled backend classes in this workspace contain the trigger-type fix: `ExecutionService` compares the manual execution request against `WorkflowGraphPlan.triggerNodeType()`, and `WorkflowGraphPlan` contains `triggerNodeType()`.

The exact live database row for workflow `d7b865dd-5ed8-4614-b193-203b02576a26` could not be inspected from this sandbox: `psql` is not installed, direct PostgreSQL JDBC socket access was blocked by sandbox policy, process inspection was blocked, and the escalation reviewer could not approve local socket access because quota was exceeded. No local database data was changed.

Given the observed browser response and the bytecode inspection, the remaining live-browser failure is most consistent with one of these two facts:

1. The running backend process is still using stale classes from before the fix and must be restarted or redeployed from the current build.
2. The actual saved workflow row has a non-manual root trigger, most likely `webhook-trigger`, despite the editor visually appearing to show a Manual Trigger.

The implementation in this workspace addresses the code-path defect by making the graph validator's root trigger type explicit and using it in `ExecutionService`. The manual browser acceptance step must be repeated after restarting the backend from the current build, or after directly inspecting the workflow row with database/API access.

## Exact Values Established

### Frontend Execute Request

Angular calls:

```ts
ExecutionApiService.startExecution(workflowId, triggerInput, timeoutSeconds)
```

The request body is:

```json
{
  "triggerInput": {},
  "timeoutSeconds": 300
}
```

The frontend does not send:

- `triggerType`
- workflow definition
- AI node metadata
- root trigger metadata

Therefore the backend request trigger type is determined by the controller/service path:

```java
ExecutionController.startExecution(...)
ExecutionService.startExecution(workflowId, request)
ExecutionTriggerType.MANUAL
```

Requested execution trigger type at the backend entry point:

```text
MANUAL
```

Expected root node type for that request:

```text
manual-trigger
```

### Current Compiled Backend Bytecode

`javap` inspection of `backend/build/classes/java/main` confirmed that the currently compiled `ExecutionService` uses:

```text
WorkflowGraphPlan.triggerNodeType()
```

for trigger validation.

The compiled comparison is:

```text
expectedTriggerNodeType.equals(graph.triggerNodeType())
```

`javap` also confirmed that compiled `WorkflowGraphPlan` exposes:

```text
triggerNodeId()
triggerNodeType()
executionOrder()
incomingConnections()
outgoingConnections()
```

This proves the current build output contains the fix. It does not prove the already-running browser backend process has loaded these classes.

### Actual Local Database Row

Status:

```text
NOT VERIFIED from this sandbox
```

Attempted read-only inspection:

- `psql` was unavailable: `command not found`.
- A temporary JDBC read-only probe was prepared using the project PostgreSQL driver.
- The JDBC connection to `jdbc:postgresql://localhost:5432/flowforge` failed in the sandbox with:

```text
java.net.SocketException: Operation not permitted
```

- A retry with escalated local socket access was not executed because automatic approval review failed due quota.
- `ps` process inspection was also blocked by sandbox policy.
- No admin token was present in this shell environment, so the workflow could not be fetched through the protected management API.

No database records, schema, or migrations were modified.

## Why The Previous Fix Did Not Resolve The Browser Failure

The previous fix is present in source and compiled classes, and the focused tests pass. The browser error occurring afterward means the browser request is not yet exercising the updated code path, or the live saved workflow row is not the same shape as the tested UI-shaped manual workflow.

The most likely operational reason is a stale backend process. The backend must be restarted after these uncommitted source changes are compiled. A running Spring Boot process does not automatically reload arbitrary backend class changes unless it is configured for that behavior and actually reloads the classpath.

The second possible reason is saved data: if workflow `d7b865dd-5ed8-4614-b193-203b02576a26` has `webhook-trigger` as its validated root trigger, then manual execution through:

```text
POST /api/workflows/{workflowId}/executions
```

should continue to fail with the same message. That would be correct strict validation, not an AI Model bug. The row needs direct inspection to distinguish this from a stale process.

## Code Changes

The implementation already made the root trigger type part of the graph contract and stopped deriving it indirectly during execution startup.

### `WorkflowGraphPlan`

Added:

```java
String triggerNodeType
```

### `WorkflowGraphValidator`

Populates the trigger type from the validated trigger node:

```java
nodes.get(triggerId).type()
```

### `ExecutionService`

Validates manual/webhook execution against:

```java
graph.triggerNodeType()
```

instead of deriving the trigger type from graph execution order or any frontend-provided value.

Manual execution remains strict:

```text
MANUAL request -> root node type must be manual-trigger
```

Webhook execution remains strict:

```text
WEBHOOK request -> root node type must be webhook-trigger
```

AI Model remains a normal processing node:

```text
ai-model
```

No AI-specific trigger exception was added.

## Files Changed

Code and test files changed for this trigger-mismatch fix:

- `backend/src/main/java/com/flowforge/runtime/WorkflowGraphPlan.java`
- `backend/src/main/java/com/flowforge/validation/WorkflowGraphValidator.java`
- `backend/src/main/java/com/flowforge/service/ExecutionService.java`
- `backend/src/test/java/com/flowforge/validation/WorkflowGraphValidatorTest.java`
- `backend/src/test/java/com/flowforge/controller/AiModelIntegrationTest.java`
- `src/app/core/services/execution-api.service.spec.ts`
- `tmp-flowforge-ai-trigger-fix-report.md`
- `tmp-flowforge-trigger-mismatch-root-cause-report.md`

No migration files were added or modified.

## Regression Test Reproduction

The closest reproducible automated path uses the actual UI-generated workflow definition shape:

- `workflowId`
- `workflowName`
- node `name`
- node `description`
- node `position`
- `selectedNodeId`
- `selectedConnectionId`
- `isDirty`
- `connections` in the editor shape
- `ai-model` serialized exactly as the frontend saves it
- normal Execute request body with `triggerInput` and `timeoutSeconds`

Regression tests:

- `AiModelIntegrationTest.manualTriggerCanStartAiModelWorkflowWithLogOutput`
- `AiModelIntegrationTest.uiGeneratedManualAiLogWorkflowSavesReloadsAndExecutesWithNormalExecuteRequest`
- `AiModelIntegrationTest.manualSetDataAiLogWorkflowExecutesWithAiAsDownstreamProcessingNode`
- `WorkflowGraphValidatorTest.aiModelIsAProcessingNodeAndDoesNotReplaceTheManualTrigger`

The UI-shaped regression verifies:

```text
Manual Trigger -> AI Model -> Log Output
POST /api/workflows/{workflowId}/executions
HTTP 202 Accepted
execution succeeds
AI output is passed downstream
```

The test uses mocked AI provider responses. No paid API calls were made.

## Test And Build Results

### Focused Backend Regression

Command:

```text
./gradlew test --tests com.flowforge.controller.AiModelIntegrationTest --tests com.flowforge.controller.ExecutionApiIntegrationTest --tests com.flowforge.controller.WebhookIntegrationTest --tests com.flowforge.validation.WorkflowGraphValidatorTest
```

Result:

```text
BUILD SUCCESSFUL
30 focused backend tests passed.
```

### Frontend Tests

Command:

```text
npm test
```

Result:

```text
9 test files passed.
33 tests passed.
```

### Full Backend Build

Command:

```text
./gradlew build
```

Result:

```text
BUILD SUCCESSFUL
Full backend test suite passed.
```

### Frontend Production Build

Command:

```text
npm run build
```

Result:

```text
Build successful.
Warning: initial bundle exceeded the 500 kB budget by 16.83 kB.
```

The bundle warning is pre-existing for this line of work and does not fail the build.

## Database Inspection Status

The existing local database was not modified.

The named workflow row was not successfully inspected from this sandbox because:

- `psql` is not installed.
- Direct JDBC socket access to localhost PostgreSQL is blocked by sandbox policy.
- Escalated local socket access could not be approved due quota.
- No admin token was present in the shell environment to use the protected API.

Recommended read-only SQL for manual verification:

```sql
select
  id,
  name,
  active,
  definition #>> '{nodes,0,id}' as first_node_id,
  definition #>> '{nodes,0,type}' as first_node_type,
  jsonb_path_query_array(
    definition,
    '$.nodes[*] ? (@.type == "manual-trigger" || @.type == "webhook-trigger").{"id": @.id, "type": @.type}'
  ) as trigger_nodes,
  jsonb_path_query_array(definition, '$.nodes[*].{"id": @.id, "type": @.type}') as node_types,
  jsonb_path_query_array(
    definition,
    '$.connections[*].{"id": @.id, "sourceNodeId": @.sourceNodeId, "targetNodeId": @.targetNodeId, "sourceHandle": @.sourceHandle, "targetHandle": @.targetHandle}'
  ) as connections
from workflows
where id = 'd7b865dd-5ed8-4614-b193-203b02576a26'::uuid;
```

Expected values for a valid manual execution:

```text
requested execution trigger type: MANUAL
expected root node type: manual-trigger
validated graph root trigger node type: manual-trigger
```

If `trigger_nodes` reports `webhook-trigger`, the manual Execute endpoint is correctly rejecting the workflow. If `trigger_nodes` reports `manual-trigger`, the browser backend process is likely stale and must be restarted from the current build.

## Manual Browser Acceptance Steps

1. Stop the running backend process.
2. Start the backend from the current workspace after the successful build.
3. Refresh the frontend page to clear any stale client bundle.
4. Inspect workflow `d7b865dd-5ed8-4614-b193-203b02576a26` and confirm its only trigger node is `manual-trigger`.
5. Click Execute.
6. Confirm `POST /api/workflows/d7b865dd-5ed8-4614-b193-203b02576a26/executions` returns:

```text
202 Accepted
```

7. If it still returns HTTP 400, capture the saved workflow definition or run the read-only SQL above. The decisive values are `trigger_nodes`, `node_types`, and `connections`.

## Remaining Limitations

The live workflow row could not be inspected by this agent due sandbox and approval-review limitations. Because of that, the exact live saved root trigger type for `d7b865dd-5ed8-4614-b193-203b02576a26` remains unverified here.

The source/build fix is present and verified by regression tests and bytecode inspection. Manual browser verification requires restarting the backend or confirming that the currently running backend is using the current compiled classes.

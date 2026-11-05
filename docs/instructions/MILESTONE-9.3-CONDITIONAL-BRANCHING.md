# FlowForge — Conditional Branching

## Technical Implementation Specification

**Document ID:** FF-M9.3
**Milestone:** 9.3
**Feature:** Conditional Branching (If/Else)
**Status:** PLANNED
**Priority:** High
**Project:** FlowForge Workflow Automation Platform

---

# 1. Purpose

This document defines the functional and technical requirements for implementing conditional branching in FlowForge.

Conditional branching allows a workflow to evaluate data against a configured condition and dynamically determine which execution path should run.

The objective is to evolve FlowForge from a primarily sequential workflow engine into a workflow automation engine capable of making decisions during execution.

This specification is intended to guide implementation using the existing Angular frontend and Java/Spring Boot backend.

# 2. Current System Context

FlowForge currently provides:

- Visual workflow editor.
- Node creation, editing and positioning.
- Connections between workflow nodes.
- Workflow persistence in PostgreSQL.
- Manual workflow execution.
- Immutable execution snapshots.
- Graph-based execution runtime.
- Node execution status tracking.
- Execution logs.
- Data passing between connected nodes.
- Set Data transformations.
- Execution history and monitoring.

The existing architecture must be extended rather than replaced.

## Technology stack

| Layer                | Technology                |
| -------------------- | ------------------------- |
| Frontend             | Angular 21                |
| Language             | TypeScript                |
| Backend              | Java 21                   |
| Framework            | Spring Boot               |
| Build system         | Gradle Kotlin DSL         |
| Database             | PostgreSQL                |
| Database migrations  | Flyway                    |
| Workflow definitions | Existing JSON/JSONB model |

# 3. Feature Overview

Introduce a new node type:

**IF / ELSE**

The node evaluates a condition and selects exactly one of two possible execution paths.

- TRUE: condition evaluates successfully.
- FALSE: condition does not match.

The selected branch continues execution.

The unselected branch must not execute.

# 4. Expected Workflow Behavior

Example:

Manual Trigger → Set Data → IF/ELSE

The IF/ELSE node evaluates:

`age >= 18`

It then selects one of two paths:

- TRUE → Log Message: Access granted.
- FALSE → Log Message: Access denied.

## Expected execution example

Input:

```json
{
  "user": "Alex",
  "age": 20
}
```

Expected result:

- Manual Trigger: SUCCEEDED
- Set Data: SUCCEEDED
- IF/ELSE: SUCCEEDED
- TRUE branch: EXECUTED
- FALSE branch: SKIPPED
- Overall execution: SUCCEEDED

For an input age of 15, the FALSE branch must execute instead.

# 5. Frontend Requirements

## 5.1 Node creation

Add an IF/ELSE node to the existing node creation system.

Requirements:

- Follow existing node design conventions.
- Display a recognizable conditional/decision icon.
- Display the node name and configured condition.
- Provide separate TRUE and FALSE output handles.
- Support normal node dragging and positioning.
- Preserve node selection and editing behavior.
- Persist configuration through the existing workflow save mechanism.

Do not introduce a separate editor or replace the existing graph implementation.

## 5.2 Node configuration panel

When a user selects the IF/ELSE node, provide a configuration interface.

Required fields:

**Condition field**

The data property to evaluate.

Example: `age`

**Operator**

Initially support:

- Equals
- Not Equals
- Greater Than
- Greater Than or Equal
- Less Than
- Less Than or Equal
- Is Empty
- Is Not Empty

**Comparison value**

The value against which the field is evaluated.

Example:

- Field: age
- Operator: Greater Than or Equal
- Value: 18

Configuration must be validated before saving or executing the workflow.

## 5.3 Output handles

The node must have two distinct outgoing connection handles:

- TRUE
- FALSE

Users must be able to connect each output to a different downstream node.

The implementation must use the existing edge and handle representation wherever possible.

The saved workflow definition must retain the information required by the backend to identify each branch.

# 6. Backend Requirements

## 6.1 Conditional evaluator

Implement conditional evaluation in the Java execution runtime.

The evaluator must:

1. Receive the node configuration.
2. Read the appropriate field from the current execution input.
3. Apply the configured operator.
4. Compare the value safely.
5. Produce a boolean result.
6. Return the selected branch.

Do not use JavaScript eval, arbitrary code execution or unsafe expression evaluation.

Use explicit, testable comparison logic.

## 6.2 Type handling

Support appropriate comparisons for:

- Strings
- Numeric values
- Booleans
- Null or missing values

Numeric comparisons must use numeric semantics rather than lexicographical string comparisons.

Invalid comparisons must produce meaningful errors rather than silently selecting a branch.

## 6.3 Graph runtime integration

Extend the existing graph runtime to understand conditional branch outputs.

Requirements:

- Identify the outgoing TRUE and FALSE edges.
- Select the correct outgoing edge.
- Execute only the selected path.
- Prevent execution of the unselected path.
- Preserve existing graph execution ordering.
- Preserve data passing between nodes.
- Support multiple downstream nodes on a selected branch if the existing graph model allows fan-out.
- Handle paths that later rejoin without executing shared downstream nodes twice.

Do not assume that edge array order determines branch selection.

Branch selection must use the actual saved connection and handle information.

## 6.4 Node statuses

Use the existing node execution status model.

Expected behavior:

| Node             | Selected path    | Unselected path |
| ---------------- | ---------------- | --------------- |
| Manual Trigger   | SUCCEEDED        | N/A             |
| Set Data         | SUCCEEDED        | N/A             |
| IF/ELSE          | SUCCEEDED        | N/A             |
| Downstream nodes | Execute normally | SKIPPED         |

Use the existing status definitions. Do not introduce incompatible status values without a justified architectural change.

# 7. Execution Logs and Monitoring

The IF/ELSE execution record must make its decision understandable.

Record:

- Evaluated field.
- Operator.
- Comparison value.
- Actual field value, where safe.
- Evaluation result.
- Selected branch.
- Execution timestamp.

Example log:

```json
{
  "nodeType": "IF_ELSE",
  "field": "age",
  "operator": "GREATER_THAN_OR_EQUAL",
  "comparisonValue": 18,
  "actualValue": 20,
  "result": true,
  "selectedBranch": "TRUE"
}
```

The execution details UI should display the selected branch and make skipped nodes distinguishable from failed nodes.

Avoid logging sensitive data unnecessarily.

# 8. Error Handling

Handle the following cases:

- Missing condition configuration.
- Unsupported operator.
- Missing input field.
- Invalid comparison value.
- Incompatible data types.
- Missing TRUE or FALSE connection.
- Invalid branch handles.
- Invalid graph structure.

Define clearly which cases are validation errors and which are runtime errors.

Errors must be visible in execution details.

A condition evaluation error must not silently select TRUE or FALSE.

# 9. Persistence and Compatibility

- Preserve the existing workflow JSON structure wherever possible.
- Store conditional configuration using the existing node configuration mechanism.
- Preserve TRUE/FALSE handle identifiers when saving and loading.
- Preserve immutable execution snapshots.
- Do not modify historical executions.
- Introduce Flyway migrations only if the existing persistence model genuinely requires them.

Existing sequential workflows must continue to execute exactly as before.

# 10. Testing Strategy

## Backend unit tests

Test:

1. String equality.
2. String inequality.
3. Numeric greater-than comparison.
4. Numeric greater-than-or-equal comparison.
5. Numeric less-than comparison.
6. Numeric less-than-or-equal comparison.
7. Boolean comparison.
8. Empty and missing values.
9. Invalid operators.
10. Incompatible types.

## Backend integration tests

Test:

1. TRUE branch execution.
2. FALSE branch execution.
3. Unselected branch marked SKIPPED.
4. Correct execution order.
5. Data passing through selected branches.
6. Multiple nodes in a selected branch.
7. Branches that later rejoin.
8. No duplicate execution of shared downstream nodes.
9. Persistence of conditional results.
10. Execution failure handling.
11. Existing sequential workflow compatibility.

## Frontend tests

Test:

1. Adding an IF/ELSE node.
2. Opening the configuration panel.
3. Changing condition fields.
4. Selecting operators.
5. Entering comparison values.
6. Saving and loading configuration.
7. Connecting TRUE and FALSE outputs.
8. Preserving connections when moving nodes.

## End-to-end acceptance tests

Test the following workflow:

Manual Trigger → Set Data → IF/ELSE

TRUE → Log Message: Access granted

FALSE → Log Message: Access denied

Run it with age 20 and age 15.

Verify that only the expected branch executes in each run.

# 11. Non-Functional Requirements

- Maintain the existing project architecture.
- Keep execution in the existing Spring Boot application.
- Maintain thread-safe execution behavior.
- Avoid unnecessary database queries.
- Avoid unnecessary frontend dependencies.
- Keep the implementation extensible for future conditional nodes.
- Maintain readable and testable code.
- Do not introduce arbitrary execution or expression-evaluation capabilities.
- Preserve existing APIs unless a change is necessary.

# 12. Implementation Constraints

The implementation must follow these rules:

1. Inspect the existing frontend and backend before making changes.
2. Do not invent a node schema or edge schema.
3. Reuse existing node registration and executor mechanisms.
4. Reuse existing graph traversal and execution context where appropriate.
5. Do not replace the graph runtime.
6. Do not introduce Redis.
7. Do not introduce Rust.
8. Do not implement AI agent functionality in this milestone.
9. Do not redesign the entire workflow editor.
10. Do not claim tests passed unless they actually ran successfully.

# 13. Documentation Requirements

After implementation:

Create:

`docs/milestones/05-conditional-branching.md`

Update:

- `docs/ROADMAP.md`
- `docs/runtime/NODE_SYSTEM.md`
- `docs/runtime/EXECUTION_ENGINE.md`
- `docs/runtime/DATA_FLOW.md`

Document:

- Node configuration.
- TRUE/FALSE handles.
- Condition evaluation.
- Branch selection algorithm.
- Skipped-node handling.
- Example saved workflow definition.
- Execution logs.
- Test results.
- Known limitations.

Documentation must reflect actual implementation.

# 14. Definition of Done

Milestone 9.3 can be marked COMPLETED only when:

- [ ] IF/ELSE node is available in the editor.
- [ ] Condition configuration works.
- [ ] TRUE and FALSE outputs can be connected.
- [ ] Saved workflows preserve conditional configuration.
- [ ] Backend evaluates conditions correctly.
- [ ] Only the selected branch executes.
- [ ] Unselected branch nodes become SKIPPED.
- [ ] Execution logs explain branch decisions.
- [ ] Data passes correctly through selected branches.
- [ ] Branch rejoining is handled correctly.
- [ ] Existing sequential workflows still work.
- [ ] Backend tests pass.
- [ ] Frontend tests pass.
- [ ] Production builds succeed.
- [ ] Documentation is updated.

**Final status must reflect verified test results.**

---

# 15. Future Extensions (Out of Scope)

The following capabilities are intentionally deferred:

- Switch/case nodes.
- Multiple conditional branches.
- Complex AND/OR condition groups.
- Nested condition groups.
- User-defined expressions.
- JavaScript expression execution.
- AI-powered decision nodes.
- Dynamic routing based on AI responses.

These may be considered in future milestones after the basic IF/ELSE implementation is stable.

---

**End of Specification**

FlowForge — Conditional Branching
Milestone 9.3

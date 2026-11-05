# FlowForge Roadmap

## Workflow execution

| Milestone | Scope | Status |
|---|---|---|
| 9.2 | Graph-based workflow execution, immutable snapshots, async dispatch, node state and data passing | Implemented |
| 9.3 | IF/ELSE conditional branching, TRUE/FALSE routing, skipped paths and rejoining | Implemented; browser-based full-stack acceptance remains outstanding |

Milestone 9.3 has passing backend tests, frontend tests, and production builds. Do not mark it COMPLETED until the browser-driven acceptance workflow is verified against the running application.

See [Conditional Branching](milestones/05-conditional-branching.md) for the node/handle contract, scheduler behavior, verification, and known limitations.

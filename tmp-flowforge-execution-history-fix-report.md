# FlowForge execution history PostgreSQL 500 fix

## Root cause

`GET /api/executions` calls `ExecutionController.listAllExecutions()`, then `ExecutionService.searchAllExecutions()`, then `ExecutionRepository.searchAll()` with pagination and the selected `Sort`.

The repository query had two `lower()` expressions: `lower(e.workflowName)` and `lower(concat('%', :search, '%'))`. The entity maps `workflowName` as a Java `String`; the disposable PostgreSQL schema confirms `executions.workflow_name` is `character varying`. The column-side expression was correctly typed. For an absent or blank search, the service passed `null` as `:search`. PostgreSQL received the nullable value in the expression-side `lower(concat(...))` as `bytea`, yielding `SQLState 42883: function lower(bytea) does not exist`. The optional `:status` predicate contains no `lower()` expression.

A new regression test first reproduced HTTP 500 and the same PostgreSQL error on `GET /api/executions?page=0&size=20&sort=newest`. The faulty query exists in `HEAD` (`433db95`) and was not introduced by the recent execution inspection UI changes.

## Fix

`ExecutionService.searchAllExecutions()` now always supplies a non-null String search pattern: `%` for absent or blank search, and `%` plus the trimmed search text plus `%` otherwise. `ExecutionRepository.searchAll()` applies `lower()` to both the workflow name and the non-null pattern. This preserves case-insensitive substring search and the existing SQL `LIKE` behavior, while avoiding binary-typed null binding. The status predicate, pageable query, and controller sort choices are unchanged.

No column type, entity mapping, migration, webhook path, or execution runtime behavior was changed.

## Files changed for this fix

- `backend/src/main/java/com/flowforge/repository/ExecutionRepository.java`
- `backend/src/main/java/com/flowforge/service/ExecutionService.java`
- `backend/src/test/java/com/flowforge/controller/ExecutionApiIntegrationTest.java`
- `tmp-flowforge-execution-history-fix-report.md`

The first three files already had unrelated uncommitted work in the working tree; those changes were preserved.

## PostgreSQL regression verification

Tests used a new, disposable PostgreSQL 16 Alpine container with a random loopback-only port and a new `flowforge_execution_history_test` database. Spring test configuration pointed explicitly to that container, used the PostgreSQL driver and dialect, Hibernate schema validation, and Flyway on **that disposable database only**. The container was stopped and removed after verification. The existing local FlowForge PostgreSQL database was not accessed or modified.

| Check | Result |
| --- | --- |
| New focused regression before fix | **Failed as expected**: HTTP 500; PostgreSQL SQLState 42883, `lower(bytea)` |
| New focused regression after fix | **Passed** on disposable PostgreSQL |
| Full `ExecutionApiIntegrationTest` class after fix | **15 passed, 0 failed, 0 skipped** on disposable PostgreSQL |
| `git diff --check` | **Passed** |

The new regression covers the exact no-search URL, an explicitly empty search, case-insensitive workflow-name matching, page size/page number with sorting, and status filtering. The full class also exercises existing execution history, snapshot, retry, cancellation, recovery, graph, and conditional-branch behavior. No frontend files changed, so frontend tests and build were not rerun. No broad backend or webhook security suite was run.

## Remaining limitations

This was an automated MockMvc verification on disposable PostgreSQL, not a manual browser check against the user's deployment. Existing workflow-name search retains SQL `LIKE` wildcard behavior for `%` and `_` in user input; this fix does not change that contract.

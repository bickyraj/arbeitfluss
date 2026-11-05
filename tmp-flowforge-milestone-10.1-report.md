# FlowForge Milestone 10.1 — AI Provider Foundation

## Implemented functionality

- Added provider-independent `AiProvider`, `AiRequest`, `AiResponse`, `AiUsage`, `AiProviderException`, and `AiProviderRegistry` types. The executor selects a registered provider by ID, so adding a provider does not require a workflow-runtime or executor branch.
- Added a working OpenAI Responses API adapter. It builds system/user messages, model, temperature, `max_output_tokens`, and `store: false`; uses the backend-only `OPENAI_API_KEY`; applies a request timeout and a 1 MiB response cap; maps text and usage; and converts authentication, rate-limit, timeout, unavailable-provider, and invalid-response failures into safe error codes. Raw provider errors and network exception text are never returned.
- Added the `ai-model` node to the existing `NodeExecutorRegistry` path. It validates configuration, resolves `{{input...}}` and `{{upstream.node-id...}}` references, invokes the provider, emits `{text, provider, model, usage?}`, and logs only safe metadata. Existing runtime persistence and downstream data propagation are reused.
- Added AI Model to the Angular palette and editor with provider, model, system prompt, user prompt, temperature, and maximum output token fields. The editor explains that the API key belongs on the backend.
- Added a save-time allowlist for AI node configuration. Unsupported credential fields and container-valued configuration are rejected before workflow persistence; the provider key is never inserted into workflow definitions or execution records by this integration.
- Documented configuration, use, limits, errors, and how to add another provider in `docs/runtime/AI_MODEL.md`.

## Files changed for Milestone 10.1

**Backend:**

- `backend/src/main/java/com/flowforge/ai/AiProvider.java`
- `backend/src/main/java/com/flowforge/ai/AiRequest.java`
- `backend/src/main/java/com/flowforge/ai/AiResponse.java`
- `backend/src/main/java/com/flowforge/ai/AiUsage.java`
- `backend/src/main/java/com/flowforge/ai/AiProviderException.java`
- `backend/src/main/java/com/flowforge/ai/AiProviderRegistry.java`
- `backend/src/main/java/com/flowforge/ai/AiConfigurationPolicy.java`
- `backend/src/main/java/com/flowforge/ai/OpenAiProvider.java`
- `backend/src/main/java/com/flowforge/executor/AiModelExecutor.java`
- `backend/src/main/java/com/flowforge/service/WorkflowService.java`
- `backend/src/test/java/com/flowforge/ai/OpenAiProviderTest.java`
- `backend/src/test/java/com/flowforge/executor/AiModelExecutorTest.java`
- `backend/src/test/java/com/flowforge/controller/AiModelIntegrationTest.java`

**Frontend and documentation:**

- `src/app/core/models/workflow-editor.model.ts`
- `src/app/features/workflows/workflow-editor/workflow-editor.ts`
- `src/app/features/workflows/workflow-editor/workflow-editor.spec.ts`
- `src/app/features/templates/templates-page.ts` (exhaustive node descriptor map)
- `docs/runtime/AI_MODEL.md`
- `tmp-flowforge-milestone-10.1-report.md`

All other modified and untracked files visible in Git status predated this milestone and were preserved. Nothing was staged or committed.

## Environment configuration

| Variable | Requirement | Default |
| --- | --- | --- |
| `OPENAI_API_KEY` | Required to execute an OpenAI node; backend process only | Empty: node fails closed with `AI_NOT_CONFIGURED` |
| `FLOWFORGE_OPENAI_DEFAULT_MODEL` | Optional model for definitions that omit `model` | `gpt-4.1-mini` |
| `FLOWFORGE_AI_TIMEOUT_SECONDS` | Optional default outbound request timeout, 1–120 seconds | `30` |

The editor's new node defaults to `gpt-4.1-mini`; its model field is editable. No key is placed in Angular assets, a database column, or a workflow setting.

## Verification

| Check | Result |
| --- | --- |
| Focused backend AI tests | 8 passed: `OpenAiProviderTest` (4), `AiModelExecutorTest` (2), `AiModelIntegrationTest` (2) |
| Full backend test suite on isolated H2 | 165 passed, 0 failed, 0 errors, 0 skipped across 22 classes |
| Backend `./gradlew build` with isolated H2 and empty `OPENAI_API_KEY` | `BUILD SUCCESSFUL`; it ran the full suite after the final validation change |
| Focused Angular editor tests | 15 passed |
| Full Angular tests | 33 passed across 9 files |
| Angular production build | Passed; existing initial-bundle budget warning, now 516.83 kB versus 500 kB budget |
| `git diff --check` | Passed |

Backend tests used explicit H2 memory settings with Flyway disabled. The OpenAI adapter tests used a loopback test server; the integration test used a mocked provider registry. No real OpenAI request or paid API call was made. The user's local PostgreSQL database was not accessed or modified. No migration was added or changed.

## Known limitations

- The only implemented provider is OpenAI; the architecture accepts future providers, while the editor currently offers OpenAI only.
- Text generation only. No streaming, tools, memory, multi-agent coordination, provider retry, or exactly-once guarantee. Retrying a workflow can make another paid request.
- Model support varies by account and model. Some models may reject temperature or token settings; the provider returns a safe normalized error rather than changing the request silently.
- FlowForge persists ordinary workflow prompt configuration and execution input/output, including generated text. The backend provider credential is never injected into them, but users must avoid placing secrets in prompts or incoming workflow data.
- Automated tests verify HTTP request construction, mapping, safe errors, validation, and end-to-end data flow with mocks. A real account/model call and operator review of billing, data handling, and rate limits remain manual acceptance steps.

## Manual acceptance

Configure `OPENAI_API_KEY` on a non-production backend, create an active workflow with Manual Trigger → AI Model → a downstream node, choose a model enabled for the account, and run it with non-sensitive sample input. Confirm generated text, usage when supplied, downstream input, and safe execution logs. This step can incur provider charges and was intentionally not performed automatically.

# AI Model node (Milestone 10.1)

FlowForge executes `ai-model` through the existing workflow runtime. The node calls a provider through `AiProviderRegistry`; the first provider is `OpenAiProvider`, using the [OpenAI Responses API](https://platform.openai.com/docs/api-reference/responses). No separate runtime, database table, or credential store is involved.

## Server configuration

Set `OPENAI_API_KEY` in the **backend process environment**. Keep it out of workflow JSON, frontend assets, browser storage, logs, and source control. A missing key lets the backend start, but an AI Model execution fails with `AI_NOT_CONFIGURED`. The adapter sends `Authorization: Bearer` to the fixed OpenAI API endpoint and requests `store: false`.

Optional environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `FLOWFORGE_OPENAI_DEFAULT_MODEL` | `gpt-4.1-mini` | Backend model when a saved AI node omits `model` |
| `FLOWFORGE_AI_TIMEOUT_SECONDS` | `30` | Default outbound request timeout; must be 1–120 seconds |

For a local shell, export the key before starting Spring Boot, for example `export OPENAI_API_KEY="$(your-secret-manager-command)"`. Do not put the key in Angular environment files or workflow settings. OpenAI account access and model compatibility must be configured separately; some models may reject `temperature` or other request settings.

## Using the node

Add **AI Model** after a Manual or Webhook Trigger, configure provider `OpenAI`, model ID, system prompt (optional), user prompt, temperature (0–2), and maximum output tokens (1–8192). The UI has no API key field. The backend rejects unsupported AI configuration fields, including credential fields, when saving a workflow.

Prompts can reference the current node input with `{{input}}` or `{{input.field}}`. Use `{{upstream.node-id.field}}` for an upstream node output available on the active path. Nested object fields use dots. Text fields are inserted as text; objects, arrays, numbers, and booleans are inserted as JSON. Missing references fail the node before any provider request. Prompt text is limited to 16,000 system and 32,000 user characters, including resolved values.

The node emits `{ "text": "...", "provider": "openai", "model": "...", "usage": { ... } }`. `usage` is omitted if the provider does not return it. Downstream nodes receive this object as their normal input. Execution logs record the provider, model, and available total token count, without prompt contents or credentials. Generated text and node inputs/outputs are persisted as ordinary execution data, so avoid placing secrets in prompts or workflow input.

## Errors and limits

Authentication failures, rate limits, timeouts, unavailable providers, malformed responses, and invalid node configuration become stable `AI_*` error codes with safe messages. Raw provider error bodies and network exception messages are not logged or returned. The OpenAI response body is capped at 1 MiB. Workflow cancellation interrupts the outbound operation through the existing execution deadline and cancellation flow.

This milestone supports text generation only. It has no streaming, tool calls, memory, multi-agent orchestration, automatic provider retry, or exactly-once guarantee. A retry of a failed workflow can make another paid provider call. OpenAI billing, rate limits, model support, and data handling follow the operator's OpenAI account settings.

## Adding another provider

Implement `AiProvider.id()` and `AiProvider.generate(AiRequest)`, map provider output to `AiResponse`/`AiUsage`, and translate failures into safe `AiProviderException` codes. Register the implementation as a Spring component. The registry accepts its identifier without changing the workflow runtime or `AiModelExecutor`. Add the provider to the editor selector when ready to expose it to users.

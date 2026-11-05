# Webhook Trigger

The Webhook Trigger starts a workflow when an external application sends an HTTP request to a backend-managed endpoint. The request creates a normal FlowForge execution with trigger type `WEBHOOK`; the existing asynchronous dispatcher runs it after the execution and node records are committed. The caller receives an acknowledgement without waiting for the workflow to finish.

## Configure a webhook

1. Add a **Webhook Trigger** as the workflow's single root trigger.
2. Choose `POST`, `GET`, `PUT`, `PATCH`, or `DELETE`. The default is `POST`.
3. Keep **Secret Token** authentication enabled unless the endpoint is intentionally public. Select **None** only when unauthenticated triggering is acceptable.
4. Save the workflow. The backend creates a persistent random endpoint ID for this workflow/node pair; saving again keeps the same URL.
5. Generate a secret token. It is returned only for that generation/rotation response and shown once in the editor. Store it securely; FlowForge retains only a SHA-256 hash and cannot retrieve the original token.
6. Activate the workflow. Both the workflow and the trigger node must be active. Secret-token webhooks remain inactive until a secret is configured. The editor reports inactive/missing configuration instead of showing the endpoint as active.

Deactivating the workflow or node disables the registration without changing its URL. Removing the trigger node or deleting the workflow removes its registration. Re-activating a saved workflow re-enables its existing endpoint when its configuration is complete.

The URL is shown in the node configuration panel and can be copied. With the default local API base path it has this form:

```text
https://your-flowforge-host/api/webhooks/{randomWebhookId}
```

The frontend management API uses a shared administrator token. Set `FLOWFORGE_ADMIN_TOKEN` to a random value of at least 32 bytes before starting the backend; for example, generate one with `openssl rand -hex 32`. The sign-in page sends it to the verification endpoint and keeps it in browser memory only. Management API requests use `Authorization: Bearer <admin-token>`; the token is not included in frontend bundles or local storage. All management `/api/**` routes require it. The inbound `/api/webhooks/{id}` route is public and uses its webhook secret instead. This is a single shared administrator identity, not per-user accounts or role-based access.

## Test request

For Secret Token authentication:

```bash
curl -X POST "https://your-flowforge-host/api/webhooks/{randomWebhookId}" \
  -H "Content-Type: application/json" \
  -H "X-FlowForge-Webhook-Secret: YOUR_SECRET" \
  -d '{"orderId":"order_123","customer":"Alex","amount":99.99}'
```

For a webhook configured with `GET`, use `curl -G` and query parameters instead. The expected accepted response is HTTP `202`:

```json
{
  "accepted": true,
  "executionId": "execution-uuid",
  "status": "QUEUED"
}
```

## Request contract and limits

- Supported methods: `POST`, `GET`, `PUT`, `PATCH`, and `DELETE`. A mismatched method returns `405` with the configured method in `Allow`.
- Supported body content types: `application/json` (including `+json`) and `text/plain`. Empty requests are allowed. Other body content types and malformed JSON return `400`.
- Request body bytes are counted while reading and capped at 1 MiB. Oversized bodies and query strings return `413`. JSON nesting is limited to 50 levels; strings are limited to 256 KiB. Query input is limited to 100 parameters, 20 values per parameter, and 2 KiB per value.
- The persisted trigger input is an object containing `body`, `query`, `headers`, `method`, and server-generated `receivedAt`. JSON body structure is preserved. Query values are represented as arrays.
- Only `content-type`, `user-agent`, and `x-request-id` are copied from request headers. Cookies, `Authorization`, and the FlowForge webhook secret are excluded. Request bodies and secret values are not written to application logs.
- The total trigger input is additionally checked against the execution service's existing 1 MiB limit before execution creation.

## Rate limiting and responses

The single-instance limiter allows up to 60 requests per minute for each webhook ID and remote socket address. Excess requests return `429`. State is in memory, bounded and expires; it resets on restart and is not coordinated across multiple application instances. When deployed behind a proxy, configure the network so `getRemoteAddr()` reflects the intended client boundary; forwarded headers are not trusted by the limiter.

| Response | Meaning |
|---|---|
| `202` | Valid request accepted and queued asynchronously |
| `400` | Malformed payload or unsupported content type |
| `401` | Missing or invalid secret token |
| `404` | Unknown, removed, or inactive webhook/workflow |
| `405` | Request method does not match the saved registration |
| `413` | Request/query/persisted trigger input exceeds its size limit |
| `429` | Per-instance rate limit exceeded |
| `500` | Temporary internal failure; response does not expose stack traces or secrets |

Authentication and registration resolution occur before execution creation. Each accepted delivery creates a separate execution; duplicate delivery is not deduplicated and exactly-once processing is not provided. Repeated legitimate requests are not silently discarded.

## Execution history and security

Webhook executions use the same immutable workflow snapshot, execution record, node-execution tracking, logs, cancellation/recovery, and history endpoints as manual runs. History displays `WEBHOOK`; the trigger node input contains the sanitized request payload and its server receipt timestamp.

Use HTTPS, keep both the administrator token and webhook secret private, rotate a webhook secret immediately if exposed, and send only data the workflow needs. Trigger input is persisted and visible to authorized workflow administrators in execution details. Avoid sending credentials or unnecessary personal data in the body, query string, or allowlisted headers.

The current administrator token is shared by all administrators and has no per-user identity, role separation, or individual revocation. The rate limiter is single-instance only. No idempotency key, distributed coordination, or replay protection is implemented.

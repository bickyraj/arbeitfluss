# Webhook Trigger — registration and Stage 3 ingress

Stage 2B provides backend-owned registrations and authenticated management APIs.
Stage 3 hardens the existing inbound path: current registration authorization and
execution creation share the workflow-parent lock and transaction, then use the
existing after-commit asynchronous dispatcher. No second execution runtime is
introduced. Frontend acceptance remains Stage 4 work.

## Operator enablement (default off)

Inbound delivery is **disabled by default**, including for existing enabled
registrations. Set `FLOWFORGE_WEBHOOK_INGRESS_ENABLED=true` in the backend launch
environment and restart to enable it. The underlying setting is
`flowforge.webhooks.ingress-enabled`. With the gate off, HTTP ingress and direct
webhook execution admission return 404 without creating execution records.
Management APIs, registration provisioning/rotation, manual execution, and
already accepted executions remain available.

This is an intentional deployment acceptance gate for previously unaccepted
inbound code. Operators must explicitly enable delivery after reviewing the
limits and deployment exposure. Test configuration enables it explicitly.
Registration `enabled` metadata describes per-registration readiness and does
not override the deployment gate; the existing editor is not yet gate-aware.

## Saved configuration and lifecycle

The actual serialized node uses `type: "webhook-trigger"`, a stable node `id`,
and a `configuration` object:

```json
{
  "id": "hook",
  "type": "webhook-trigger",
  "configuration": {
    "method": "POST",
    "authentication": "secret-token",
    "active": true,
    "responseMode": "immediate"
  }
}
```

Supported methods are POST, GET, PUT, PATCH, and DELETE; the default is POST.
Authentication is `secret-token` (default) or `none`. Node `active` is a boolean
and defaults to true when omitted. `responseMode` is retained for compatibility;
ingress always returns the immediate acknowledgement described below, without waiting for workflow completion.

Workflow create/update synchronizes registrations in the same transaction.
New webhook nodes receive IDs generated from 32 SecureRandom bytes, encoded
URL-safe Base64 without padding. Saving the same workflow/node pair preserves
its public ID and secret hash while updating the configured method and state.
Removing the node removes its registration; deleting the workflow removes its
registrations. Re-adding a removed node provisions a new public ID.

Effective enabled state requires an active workflow, an active webhook node,
and either authentication `none` or an explicitly generated secret. A newly
saved Secret Token registration remains disabled until generation. Deactivation
preserves its public ID and hash. Switching authentication to `none` clears the
old hash; switching back requires explicit secret generation.

The registration table owns endpoint identity, method, state and hash. Only
`method`, `authentication`, `active`, and `responseMode` are retained in webhook
configuration; client-supplied IDs, URLs, hashes and secret fields are stripped
before any workflow database flush. Malformed registration configuration fails
with 400; persistence or synchronization failure rolls back the complete
workflow transaction. Workflow update/delete and secret rotation lock the parent
workflow row to serialize registration mutations. Startup discovers IDs only and
reconciles each under the same lock in an isolated transaction; malformed legacy
workflows are skipped with a generic warning. Webhook node IDs must contain
1–255 ASCII letters, digits, underscores or hyphens. Invalid legacy IDs are not
rewritten or deleted; they require an explicitly approved correction.

Registration parsing is independent of runtime graph validation. Persisting a
registration does not certify that the graph can execute. Ingress also validates the current execution graph before creating an execution;
an invalid graph creates no execution. Workflow activation updates send only
`active` and preserve the saved definition; the existing frontend already uses
this partial-update behavior.

## Management API

All routes below require `Authorization: Bearer <administrator-token>` using the
existing deployment `FLOWFORGE_ADMIN_TOKEN`. Missing/invalid credentials return
401; missing administrator configuration returns 503. These nested workflow
routes are never covered by the public inbound-route exception.

| Method and route | Response |
|---|---|
| `GET /api/workflows/{workflowId}/webhooks` | 200, array of safe metadata; empty when there are no registrations |
| `GET /api/workflows/{workflowId}/webhooks/{nodeId}` | 200, one registration's safe metadata |
| `POST /api/workflows/{workflowId}/webhooks/{nodeId}/secret` | 200, explicitly generates or rotates the secret and returns it once with safe metadata |

Metadata contains `nodeId`, `webhookId`, `httpMethod`, `authenticationMode`
(`NONE` or `SECRET_TOKEN`), `enabled`, `secretConfigured`, `createdAt`, and
`updatedAt`. It contains neither the internal registration UUID nor secret
material. Unknown workflows/registrations return 404. Secret generation for
`NONE` authentication returns 400. Error responses use the existing backend
error contract.

Secret generation/rotation returns:

```json
{
  "secret": "<one-time generated value>",
  "registration": {
    "nodeId": "hook",
    "webhookId": "<opaque public ID>",
    "httpMethod": "POST",
    "authenticationMode": "SECRET_TOKEN",
    "enabled": true,
    "secretConfigured": true,
    "createdAt": "<ISO-8601 timestamp>",
    "updatedAt": "<ISO-8601 timestamp>"
  }
}
```

This response uses `Cache-Control: no-store`. The secret uses 32 SecureRandom
bytes, encoded URL-safe without padding. Only its SHA-256 digest is persisted;
rotation replaces the prior digest transactionally and reconciles enabled state.
The plaintext cannot be retrieved through metadata or ordinary workflow GETs.
No credentials are logged. Inbound verification compares SHA-256 digests using
`MessageDigest.isEqual`.

No reliable configured public base URL exists, so metadata returns the opaque
public ID rather than inventing a host. A client may construct the endpoint path
from its configured API base plus `/webhooks/{webhookId}`. A relative API base
produces a relative path, not a full externally reachable URL. Deployment URL
handling remains a later frontend/inbound concern.

## Database and verification

The existing `V4__create_webhook_registrations.sql` migration defines UUID keys,
a unique public ID (with its PostgreSQL unique index), unique `(workflow_id,
node_id)`, a workflow association index, an `ON DELETE CASCADE` workflow foreign
key, method/authentication constraints, nullable SHA-256 hash, enabled state,
and timezone-aware timestamps consistent with the execution tables.
V4 was already committed before this stage and is reused unchanged. Old
migrations must not be edited. Flyway applies it at deployment startup;
Hibernate uses schema validation.

Registration integration tests cover creation, stable IDs/hashes, method and
activation updates, missing secrets, rotation, metadata exclusion, untrusted
configuration stripping, deletion, invalid input, rollback after flushed writes,
and administrator authentication. The full backend suite uses H2; schema and
registration behavior are also checked against disposable PostgreSQL with
Flyway enabled and Hibernate `ddl-auto=validate`.

## Inbound delivery and atomic authorization

Endpoint shape: `/api/webhooks/{webhookId}`, where the backend-generated ID has
43 URL-safe characters. POST is the only inbound method exempt from administrator
bearer authentication. GET/PUT/PATCH/DELETE registrations remain supported, but
those HTTP methods also require the administrator bearer header under the
unchanged Stage 2A filter. All deliveries must match the saved HTTP method;
Secret Token mode additionally requires one `X-FlowForge-Webhook-Secret` header.
Do not place webhook credentials in query parameters, cookies, or JSON.

Preliminary registration/method/secret checks reject invalid requests cheaply.
The limiter runs for known enabled endpoints before authentication/body parsing,
including failed-secret attempts. Body reading and parsing occur outside the
workflow lock, preventing a slow upload from holding that lock.

After parsing, the authoritative execution service starts a fresh transaction:

1. Discover only the parent workflow ID from the public endpoint ID.
2. Acquire the same pessimistic workflow lock used by saves, startup sync,
   rotation and deletion, loading the current workflow in that transaction.
3. Reread the registration and revalidate enabled/workflow/node state, method,
   authentication mode, digest, and matching current node configuration.
4. Validate the graph and bind its root node ID to the registration. Snapshot
   the locked workflow, persist one execution plus node records/logs, and commit.
5. Dispatch through the existing after-commit asynchronous runtime.

No detached preliminary registration is trusted to create an execution. Changes
committed before the authoritative lock/read are respected, including rotation,
node replacement/deactivation, method/authentication changes and deletion.
Concurrent management mutations wait until execution creation commits, or vice
versa. A later rotation/deactivation does not cancel an execution already
accepted under the earlier valid state. Rejected requests create no executions.

HTTP 202 contains `accepted: true`, `executionId` and `status: "QUEUED"`. The
worker can finish before the caller inspects history; 202 does not wait for it.
Each accepted delivery creates one execution; repeated deliveries are distinct.

## Request input and limits

The persisted trigger input contains `body`, `query` (arrays of values), `headers`,
`method`, and server-generated `receivedAt`. JSON structure is retained. Empty
bodies produce a null body; `text/plain` produces a string.

- Raw body limit: 1 MiB, checked against declared length and while reading at
  most 1 MiB + 1 byte, including when length is unknown. The final serialized
  input envelope also must fit 1 MiB; a body at the raw limit can exceed this.
- Supported concrete body types: `application/json`, `application/*+json`
  concrete subtypes, and `text/plain`, with UTF-8 encoding. Wildcards,
  unsupported types/charsets, invalid UTF-8, malformed/trailing JSON, duplicate
  JSON keys and excessive JSON depth/string sizes are rejected.
- JSON depth limit: 50; JSON string limit: 256 KiB. Query string: 8 KiB;
  at most 100 parameter names, 20 values per name, 256 characters per name and
  2,048 characters per value. The serialized input limit still applies.
- Only content-type, user-agent and x-request-id headers are copied, each at most
  2,048 characters. Authorization, cookies, the webhook secret and arbitrary
  custom headers are excluded. Secrets and full bodies are not logged.
- Body/query data is user data and is persisted in history. Do not send secrets
  or unnecessary personal data there; allowlisted headers are not a data vault.

| Status | Meaning |
|---|---|
| 202 | Execution committed and queued through the existing dispatcher |
| 400 | Invalid payload/content type/encoding or execution graph |
| 401 | Missing, incorrect, or duplicate webhook-secret headers (or missing administrator bearer on non-POST routes) |
| 404 | Gate off, unknown/deleted/disabled endpoint, invalid public-ID format or inconsistent registration/node state |
| 405 | Request method mismatch; Allow contains the configured method |
| 413 | Raw body, query or serialized trigger input exceeds its limit |
| 429 | Local per-endpoint/socket-address rate budget exceeded |
| 500 | Generic unexpected server failure; no stack trace or secret details |

Authentication remains necessary for management routes regardless of the gate.
The Stage 2A filter's exact public-path shape also applies: paths outside that
shape may receive administrator 401 before controller-level ID validation.

## Rate limiting and limitations

The existing thread-safe limiter permits 60 attempts per fixed 60-second window
per webhook ID and socket remote address. Authentication failures consume the
budget. State is capped at 10,000 keys and expired entries are reclaimed;
capacity exhaustion fails closed with 429. Unknown/disabled endpoints do not
allocate limiter keys. Forwarded headers are not used as identity.

This limiter is single-instance, resets on restart, and is not distributed.
Configure a trusted network/proxy boundary; do not assume client-supplied
forwarded headers are trusted. TLS, request/read timeouts and external traffic
controls remain deployment responsibilities. No Redis or new delivery service
is introduced.

There is no exactly-once delivery guarantee, deduplication, replay protection,
or idempotency contract. Existing dispatcher failure/recovery semantics still
apply. The shared administrator token has no per-user identity, roles,
individual revocation or audit attribution. Stage 4 must verify the frontend
configuration UX, gate visibility and externally reachable URL handling.

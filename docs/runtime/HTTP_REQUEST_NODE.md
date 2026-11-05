# HTTP Request Node

The `http-request` node makes one bounded HTTP/HTTPS request through the normal FlowForge workflow runtime. Its JSON result is saved as the node output and passed unchanged to downstream nodes. It does not create separate execution records or bypass graph scheduling.

## Configuration

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
  "body": {}
}
```

- Methods are GET, POST, PUT, PATCH, and DELETE. GET is the default.
- Query parameters are added with a URI builder, preserving existing URL parameters and escaping names and values.
- Headers and query parameters can be enabled or disabled individually. Header values are stored in the workflow definition; avoid long-lived secrets until FlowForge has a credentials manager.
- Request bodies support JSON only and only for POST, PUT, and PATCH. There is no expression interpolation syntax in the current node system, so body values are sent literally; incoming workflow data remains available through the existing execution context but is not templated into request fields.
- Response format can be Auto, JSON, or Text. Auto parses JSON media types (`application/json` and `+json`) and otherwise returns UTF-8 text. HTML is returned as text.
- Request timeout defaults to 30 seconds, must be 1–3600 seconds, and is capped by the remaining workflow execution deadline. The overall workflow timeout defaults to 300 seconds.
- Redirect following is disabled by default. When enabled, each redirect target is revalidated; at most five redirects are followed, HTTPS-to-HTTP downgrades are rejected, and sensitive credential-like headers are removed after a cross-origin redirect.

## Output and errors

Successful output has this shape:

```json
{
  "statusCode": 200,
  "headers": { "content-type": "application/json" },
  "responseFormat": "JSON",
  "body": { "userId": 123 },
  "success": true
}
```

Only a safe response-header allowlist is persisted. JSON bodies are parsed into JSON values; text bodies remain strings. HTTP statuses outside 200–299 fail the node and execution, stop downstream scheduling, and retain the status, safe headers, and bounded response body on the failed node record (`success` is false). Connection, DNS, TLS, timeout, invalid configuration/response, and SSRF-policy failures use safe error messages/codes and do not include raw exception text or request headers.

Response bodies are streamed and capped at 5 MiB. Larger responses fail with `RESPONSE_TOO_LARGE`. Execution details show the status, parsed body, safe headers, duration, and errors; output previews are capped at 30,000 rendered characters.

## Security and operational limits

- Only absolute HTTP and HTTPS URLs without embedded credentials are accepted.
- A custom Apache HttpClient DNS resolver validates and pins every resolved destination used by the connection. Loopback, private, link-local, multicast, unspecified, reserved, metadata, IPv4-mapped private IPv6, documentation and 6to4 IPv6 ranges are blocked. Every redirect uses the same policy.
- Automatic redirects and cookies are disabled in the client. Configured credential-like headers are not logged or returned, and response headers are allowlisted.
- Request and response timeouts are bounded by the workflow deadline. Cancellation aborts the active HTTP request where supported. Cancellation and timeout do not undo remote side effects; exactly-once execution is not guaranteed, and retrying an execution may repeat an HTTP request.
- Request and response bodies are not written to execution logs. Response bodies are persisted as node output by design; users should avoid returning secrets or sensitive personal data from external services.
- This milestone does not add a credentials manager, OAuth, advanced pagination, automatic HTTP retries, file transfer, or browser-based website extraction.

## Tests

HTTP executor tests use a local mock HTTP server. The test-only resolver permits loopback solely for those deterministic tests; production uses the strict public-destination resolver and has no loopback exception.

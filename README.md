# Thinklab Platform Gateway Service

**Version:** v1.0.0-BIAN

**Status:** Reference implementation (ThinkLab portfolio project)

## Overview

The Platform Gateway is the single public entry point of the ThinkLab platform (ADR-022). It routes every
request by the first path segment — the BIAN Service Domain (`/it-asset-registry/...`,
`/party-authentication/...`) — to the matching internal upstream, relaying method, path, query, body and
headers (including `Authorization`, so each upstream still verifies the token itself) unchanged. It holds no
business logic and no persistence: routing, a per-caller token-bucket rate limit and, when
`thinklab.security.enabled=true`, bearer-token verification are its entire job.

Internal-only endpoints (service-to-service login, the published revocation list) are on the gateway's
`gateway.denied-paths` list and answer 404 exactly like an unconfigured domain, so their existence is never
revealed to a public caller.

Built with Java 21 and Micronaut 4.4.2 on a strict Hexagonal Architecture and a fully reactive stack
(Project Reactor, the Micronaut declarative HTTP client).

## Technology Stack

* **Runtime:** Java 21 LTS
* **Framework:** Micronaut 4.4.2 (AOT optimized, reflection-free DI and Serde)
* **Reactive Engine:** Project Reactor (Mono)
* **Security:** `thinklab-service-kit` `SecurityFilter` (ES256 JWT, RBAC, tenant/executor/role derived from the token)
* **Observability:** W3C Trace Context, SLF4J/Logback, Reactor MDC bridge, Micrometer/Prometheus
* **Testing:** JUnit 5, Mockito, Reactor Test, a real-server integration suite against a fake upstream

## Routing

```text
gateway:
  routes:
    <bian-service-domain>: <upstream base URL>
  denied-paths: [ <path prefix>, ... ]
  max-body-bytes: 1048576
  rate-limit:
    requests-per-second: 50
    burst: 100
```

`GET/POST/PUT/PATCH/DELETE /{+path}` is the single catch-all route (`GatewayController`). `RouteTable` picks
the upstream from the first path segment; an unconfigured domain and a denied path are indistinguishable,
both a 404. `UpstreamProxy` builds the outbound request, drops hop-by-hop headers (`Connection`,
`Transfer-Encoding`, `Host`, …), adds `X-Forwarded-For`, `X-Forwarded-Host` and `X-Forwarded-Proto`, and
relays the upstream's status, body and headers unchanged — including a 4xx/5xx from the upstream itself.

The gateway serves its own `/health`, `/health/liveness`, `/health/readiness`, `/metrics`, `/prometheus`,
`/loggers`, `/info`, `/swagger-ui/**` and `/swagger/**` directly — the catch-all never sees them. The
management endpoints depend on `GatewayController` never declaring `@Produces(MediaType.ALL)`; the two
static-resource mappings depend on `GatewayController` checking Micronaut's own `StaticResourceResolver`
before forwarding a `GET` to the upstream (see ADR-022's two addenda for both root causes).

## Rate limiting

`RateLimiter` is a per-caller token bucket (`gateway.rate-limit.requests-per-second` / `burst`), keyed by the
authenticated `X-Executor` when present and by remote address otherwise, so an anonymous flood (e.g. login
attempts) never exhausts a legitimate user's budget. Denied requests get `429` with a `Retry-After` header
and stale buckets are pruned so the map cannot grow without bound. Health, Prometheus and metrics endpoints
are exempt.

## Audit recording (ADR-023)

With `GATEWAY_AUDIT_ENABLED=true` every mutating request that carries a tenant is appended to the compliance ledger after it
completes - asynchronously and fail-open, so a ledger outage never touches the request (it is counted in `gateway.audit.dropped`).
Off by default. See ADR-023.

What is recorded is chosen by an allow-list (ADR-024): the tenant, the actor, the method and the path with identifiers masked, the Service Domain, the first resource id and the status - never the query string, a body, a token or any other header. A sign-in attempt is recorded too, but of its body only the organisation id is used: the email becomes a keyed pseudonym (`GATEWAY_AUDIT_PSEUDONYM_KEY`; without a key it is `login:unkeyed`) and **the password is never read into an entry, logged or forwarded**.

## Pseudonym lookup for investigations (ADR-027)

With `GATEWAY_INVESTIGATION_ENABLED=true`, `POST /gateway/v1/investigation/pseudonym` (body `email` + `reason`) tells an ADMIN the keyed
pseudonym a KNOWN person's sign-ins are recorded under, and how to read the ledger by it. Forward only (no pseudonym-to-person table);
the lookup is recorded on the ledger *before* anything is disclosed (fail-closed), the reason must carry no personal data, and each
investigator is limited to `GATEWAY_INVESTIGATION_MAX_PER_HOUR` (20). Off by default (404). Steps in `docs/runbook-investigation.md`.

## Plan-based feature gating (ADR-026)

With `GATEWAY_ENTITLEMENTS_ENABLED=true` a request that carries a tenant for a gated Service Domain (`gateway.entitlements.features`,
by default `it-discovery`, `compliance-audit-ledger` and `identity-federation`) is turned away with `403 ERR-GTW-00403` when the
organisation's plan does not include the feature. Billing is asked at `GATEWAY_BILLING_URL`; answers are cached for
`GATEWAY_ENTITLEMENTS_CACHE_TTL` (30s); it is fail-open when billing cannot answer, and sign-in routes (no tenant) are never judged.
Off by default. See ADR-026.

## Session cookie (ADR-025)

The refresh token never reaches page scripts: the gateway keeps it in an `HttpOnly`, `Secure`, `SameSite=Strict` cookie (`gateway.session-cookie.*`).

| Route | What it does |
|---|---|
| `POST /gateway/v1/session/refresh` | Exchanges the cookie for a new access token and rotates the cookie (needs `X-Requested-With: thinklab-web`) |
| `POST /gateway/v1/session/logout` | Revokes the session and clears the cookie (same header) |
| `GET /identity-federation/v1/login/callback` | Proxied, but the refresh token in the answer is moved into the cookie and the browser is redirected to the web app |

`/party-authentication/v1/session/federated` is internal and denied here.

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-GTW-00401` | 401 | No active session to refresh (no refresh cookie, or the identity service refused it) |
| `ERR-GTW-00403` | 403 | A session endpoint was called without the web app's `X-Requested-With: thinklab-web` header |
| `ERR-GTW-00404` | 404 | No route configured for the domain, or the path is denied |
| `ERR-GTW-00413` | 413 | Request body exceeds `gateway.max-body-bytes` |
| `ERR-GTW-00429` | 429 | Rate limit exceeded (`Retry-After` header carries the wait, in seconds) |
| `ERR-GTW-00502` | 502 | The upstream service could not be reached |
| `ERR-GTW-00504` | 504 | The upstream service did not answer in time |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## Security (ADR-020, ADR-021, ADR-022)

Off by default (`thinklab.security.enabled=false`), matching every other service. When enabled, the gateway
only **verifies** tokens: public keys come from the issuer's JWKS (`thinklab.security.jwks-url`) and revoked
sessions are polled from `thinklab.security.revocation-url`. Login, refresh, logout and the JWKS document are
public paths (`thinklab.security.public-paths`) and route through the gateway like anything else; the
service-to-service token endpoint and the raw revocation list are `gateway.denied-paths` instead — an
internal caller reaches those services directly, never through the public gateway.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.

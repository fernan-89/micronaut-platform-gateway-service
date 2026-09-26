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

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
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

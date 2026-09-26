# ADR-022: Platform Gateway — routing, rate limiting and security posture

## Status
Accepted

## Context
Every service so far has been reachable directly, on its own port, trusting whatever `X-Tenant-Id` and
`X-Executor` a caller sent until ADR-020/ADR-021 introduced real authentication. Journey 3 called for a
"dedicated gateway": a single public entry point, so no service needs its own edge concerns (CORS, rate
limiting, a public port) and a client only needs to know one address.

## Decision

### A thin, stateless reverse proxy
`micronaut-platform-gateway-service` (port 8088) holds no business logic. `RouteTable` maps the first path
segment (the BIAN Service Domain) to an upstream base URL from `gateway.routes`; `UpstreamProxy` forwards
method, path, query, body and headers — **including `Authorization`** — and relays the upstream's answer,
status and headers unchanged. Each upstream still verifies the token itself (defence in depth: a
misconfigured or bypassed gateway is not a full authentication bypass).

### 404, not 403, for what the gateway will not serve
An unconfigured Service Domain and a path on `gateway.denied-paths` (the service-to-service token endpoint,
the raw revocation list — both meant for direct east-west calls, never a public client) are handled
identically: a 404 `ERR-GTW-00404`. The gateway never confirms or denies that an internal endpoint exists.

### Timeouts and size are mapped to their own RFC 7807 codes
The upstream HTTP client is a **separate** named client (`gateway-upstream`, its own
`micronaut.http.services.gateway-upstream.*` timeouts) so the gateway's own inbound timeout budget is never
coupled to how long it waits on an upstream. A read timeout is `ERR-GTW-00504` (504); any other client-side
failure (connection refused, DNS) is `ERR-GTW-00502` (502); a request body over `gateway.max-body-bytes` is
`ERR-GTW-00413` (413), rejected before the upstream is even contacted.

### Rate limiting is per caller, at the edge
`RateLimiter` is a token bucket per caller (`gateway.rate-limit.requests-per-second` / `burst`), keyed by the
authenticated `X-Executor` (derived from the verified token by `SecurityFilter`, upstream in the filter
chain) when present, and by remote address otherwise — so an anonymous flood (credential stuffing against
`session/initiate`) burns its own bucket, never a legitimate user's. A denied request is `429`
`ERR-GTW-00429` with `Retry-After`. Buckets for callers that have gone quiet are pruned so the map is bounded.

### Security: verify only, never issue
The gateway runs the shared `SecurityFilter` (ADR-020/ADR-021) with the issuer's **public** keys only
(`thinklab.security.jwks-url`) — it has no private key and cannot mint tokens. `thinklab.security.enabled`
stays off by default, exactly like every other service, so the local stack keeps working without it.

## Consequences
- Positive: exactly one public port; upstream services can stay on their internal ports; rate limiting and
  the security posture are enforced in one place instead of copy-pasted; each finding (404 vs denied, 502 vs
  504, 429) is machine-distinguishable via `error_code`.
- Negative: the gateway is a single point of failure and adds one network hop to every call; its own
  availability now matters as much as the identity service's. Response streaming is not implemented yet (the
  body is buffered in memory up to `gateway.max-body-bytes`), so it is unsuitable for large uploads or
  downloads without raising that limit.

## Addendum — live-found bug: `@Produces(MediaType.ALL)` outranks every literal route
The catch-all `GatewayController` (`@Controller("/") @Get("/{+path}")`, etc.) originally declared
`@Consumes(MediaType.ALL) @Produces(MediaType.ALL)` at the class level, so the proxy would accept and
relay any content type without interpreting it. A live run of `start-local-stack.ps1` found the
gateway's own `/health/readiness` — and, on further probing, `/health`, `/metrics`, `/prometheus`,
`/loggers` and `/info` — all 404ing through the gateway's own `GlobalExceptionHandler`
(`ERR-GTW-00404`) instead of being served by Micronaut's built-in management endpoints, even though
`endpoints.health.kubernetes.enabled: true` (the same config every other service uses successfully) was
set. The unit/integration test that should have caught this
(`GatewayIntegrationTest > management endpoints are served by the gateway itself`) already existed and
already asserted `/health/liveness`, which shows the regression was introduced after that test was last
green — a reminder that `gradlew check` passing once is not the same as it having been re-run after every
later change.

Bisected empirically (moving `endpoints.all.path` to a different prefix did **not** help; only removing
the annotation did): declaring `@Produces(MediaType.ALL)` anywhere on this controller — class or method
level — made Micronaut's route resolution rank the catch-all above every literal route in the
application, regardless of how many literal segments the competing route had. `@Consumes(MediaType.ALL)`
alone, kept only on the body-carrying methods (`POST`/`PUT`/`PATCH`), is sufficient to accept a non-JSON
request body (the original motivation) without that side effect. Fixed by moving `@Consumes(MediaType.ALL)`
to method level and dropping `@Produces(MediaType.ALL)` entirely.

## Addendum — live-found bug: `/swagger-ui/**` and `/swagger/**` never reachable at all
Even after the fix above, `/swagger-ui/**` and `/swagger/**` (both `router.static-resources` mappings, not
`@Endpoint` beans) still 404'd through the catch-all. This is a *different* bug from the one above, and
`@Produces`/`@Consumes` placement has nothing to do with it: Micronaut's static-resource serving is a
**fallback** that only runs when no controller route matches a request at all. `GatewayController`'s
`/{+path}` matches literally every path, so that fallback code path is structurally unreachable — no amount
of reordering or reprioritizing `@Produces`/`@Consumes` changes that, which is why moving the mapping to
another path prefix (tried during the first bisection, before the root cause above was isolated) made no
difference either.

Confirmed empirically by temporarily changing the catch-all's own `@Get` mapping to a path that could not
possibly match `/swagger-ui/**` (rebuilding, restarting the service, and confirming `/swagger-ui/index.html`
started returning 200) before reverting that change and fixing the real cause. Fixed by injecting Micronaut's
own `io.micronaut.web.router.resource.StaticResourceResolver` bean into `GatewayController` and checking it
inside `get()`, before delegating to `UpstreamProxy`: if `StaticResourceResolver.resolve` finds a matching
classpath resource for the requested path, its bytes are read and returned directly (content type derived
from the extension via `MediaType.forExtension`, best-effort — no extension means no `Content-Type` header,
matching how a real static-file server would behave); otherwise the request forwards to the upstream exactly
as before. `POST`/`PUT`/`PATCH`/`DELETE` are untouched, since none of the configured mappings serve anything
but `GET`.

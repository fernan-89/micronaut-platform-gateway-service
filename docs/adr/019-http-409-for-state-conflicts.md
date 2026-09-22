# ADR-019: HTTP 409 Conflict for Every State Conflict

## Status
Accepted — supersedes the status-code decision of [ADR-017](017-route-lifecycle-state-machine-and-http-422.md)
(the lifecycle FSM and the deployment policy in ADR-017 are unchanged).

## Context
ADR-017 reported illegal lifecycle moves as HTTP 422 while the four sibling Service Domains report the
same class of failure as 409. A platform-wide client had to know which service used which status.

RFC 9110 defines the two codes precisely:

- **409 Conflict** — the request is valid but cannot be applied because of the *current state* of the
  target resource. The client may succeed later if the state changes.
- **422 Unprocessable Content** — the request is well formed but its *content* is semantically
  invalid on its own, whatever the state of the resource.
- **400 Bad Request** — malformed syntax, a missing header or an unparsable identifier.

An illegal transition (`DEPLOYED -> PROVISIONED`), an idempotent self-transition, mutating a
decommissioned route and deploying an route that has no location are all decided by the aggregate's
current state, so they are 409.

## Decision
1. Platform contract: **409 for every state conflict** (FSM violations, duplicates, window collisions),
   **422 only** for request content that is invalid regardless of state (bean validation cannot express
   it), **400** for malformed input.
2. `InvalidRouteStatusException` now carries `ERR-GTW-00409` and maps to 409, exactly like a duplicate
   serial number. The `detail` member tells the two apart; both share `ERR-GTW-00409`, as
   `ERR-USR-00409` does in the User domain.
3. `ERR-GTW-00422` is retired. The Postman suite, README error catalog and tests were updated.

## Consequences
- Positive: one predictable error contract across the platform; clients need no per-service rules.
- Negative: a breaking change for any client that matched on 422 / `ERR-GTW-00422`. The service is
  pre-release and had no external consumers, so no compatibility shim is provided.

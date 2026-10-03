# ADR-026: Plan-Based Feature Gating at the Gateway

## Status
Accepted

## Context
`subscription-billing-service` answers "does this organisation's plan include feature X, and up to what limit?" (`entitlement/evaluate`), but until now only the web app asked. Nothing stopped an organisation on a plan without the audit ledger, discovery or single sign-on from calling those APIs directly. Enforcing it inside every service would touch seventeen repositories and repeat the same check everywhere.

## Decision
- The gateway enforces it, once: `EntitlementFilter` maps the first path segment (the BIAN Service Domain) to a plan feature through configuration (`gateway.entitlements.features`: `it-discovery: discovery`, `compliance-audit-ledger: audit`, `identity-federation: sso`), asks billing through `EntitlementGate`, and answers **403 `ERR-GTW-00403`** ("Your plan does not include 'audit'.") without forwarding the request when the answer is no.
- **Only requests that carry a tenant are judged.** A sign-in (the federation login routes) has no tenant yet, so a plan can never lock a person out of signing in; a domain that is not in the map is never judged.
- **Fail-open**, like billing's own evaluation (billing ADR-032): billing down, slow, or answering something unreadable lets the request through (counted in `gateway.entitlement.unavailable`). A billing outage must not take the product down for every customer. The trade accepted: during an outage the plan limits are not enforced.
- Answers are cached per organisation and feature for `gateway.entitlements.cache-ttl` (30 seconds), bounded to 10 000 entries (the cache starts over when full). A plan change or a suspension is therefore felt within that time. Failures are never cached, so recovery is immediate.
- The filter runs after the rate limiter and the audit filter, so a refusal is itself recorded on the ledger when it is a mutation.
- **Off by default** (`GATEWAY_ENTITLEMENTS_ENABLED=false`): a stack without billing behaves exactly as before. Because an organisation with no subscription falls back to the default plan (e.g. HOMELAB, which leaves `audit` out), turning it on makes that plan's choices real - an operator must decide the default plan first.
- Only on/off features are enforced here. A **quantity** limit (`assets: 25`) needs a count that only the owning service has, so counting limits stay a per-service follow-up.

## Consequences
- Positive: one place, one configuration, no change to any other service; the web app and direct API callers see the same rule.
- Negative: an extra billing call per organisation and feature every 30 seconds per gateway instance; a plan change is not instantaneous; the map from domain to feature is coarse (all methods of a domain, not per operation).
- Proof: unit tests at 100% coverage, and `plan-gating-smoke.ps1/.sh` against a second gateway with gating on (a plan without the feature is refused, a plan change and a suspension are felt, unlisted domains and sign-in are untouched).

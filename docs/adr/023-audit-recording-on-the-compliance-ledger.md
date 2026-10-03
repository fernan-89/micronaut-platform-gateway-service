# ADR-023: The Gateway Records Every Mutating Request on the Compliance Ledger

## Status
Accepted

## Context
Every external mutation passes through the gateway, which makes it the one place that can give the platform
a complete API-call audit trail without changing the thirteen services behind it (ledger ADR-032).

## Decision
- With `gateway.audit.enabled=true` (env `GATEWAY_AUDIT_ENABLED`, **off by default**), a server filter running
  after the rate limiter appends one entry to the compliance ledger for each `POST`, `PUT`, `PATCH` or
  `DELETE` that carries a valid `X-Tenant-Id`, once the status the caller will receive is known.
- The entry: `source=platform-gateway`, `actor` = `X-Executor` (`unknown` when absent), `action` = method plus
  the path with every UUID masked as `{id}`, `resourceType` = the Service Domain (first path segment),
  `resourceId` = the first UUID in the path, `detail` = `status=NNN`. Fields are truncated to the ledger's limits
  instead of being rejected.
- Not recorded: reads, requests without a tenant (sign-in carries the organisation in its body), and the
  ledger's own domain.
- **Asynchronous and fail-open.** The append is fire-and-forget on its own short-timeout client
  (`micronaut.http.services.gateway-audit`: 1 s connect, 2 s read). A ledger that is down, slow or rejecting is
  logged and counted (`gateway.audit.dropped`, with `gateway.audit.recorded` for the successes) and never
  affects the request.
- The ledger is also routable through the gateway (`compliance-audit-ledger`) for the web app.

## Consequences
- Positive: platform-wide coverage of external mutations from one small component.
- Negative: it records that a call was made and how it ended, not what changed; and a ledger outage leaves a
  hole the chain cannot show - watch `gateway.audit.dropped`. A deployment that cannot tolerate holes needs a
  fail-closed mode (hold or queue the append), which is not built.
- Negative: it trusts `X-Tenant-Id` and `X-Executor` as the rest of the platform does; with security on they are
  derived from the verified token, with it off they are whatever the caller sent.

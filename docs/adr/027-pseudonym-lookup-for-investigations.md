# ADR-027: Pseudonym Lookup for Investigations - Forward Only, and Recorded First

## Status
Accepted

## Context
Sign-ins are recorded on the compliance ledger under a keyed pseudonym of the email (`login:<hmac>`, ADR-024), so the ledger holds no personal data and erasure is possible by destroying the key. The price is that an investigator who has a person's name has no way to find what that person did: the pseudonym cannot be reversed (and must not be), and the key lives only in the gateway.

## Decision
- **Forward lookup, never reverse.** `POST /gateway/v1/investigation/pseudonym` takes the email of a person the investigator already has and a reason, and answers the pseudonym (`login:<hmac>`) plus the ledger query to run (`GET /compliance-audit-ledger/v1/retrieve?actor=login:<hmac>`). There is no table from pseudonym to person, so this can only help investigate someone you already have a name for; it cannot be used to unmask an entry.
- **ADMIN only** when the platform runs with security on (the role comes from the verified token); with security off there is no role to check, as everywhere else on the platform. The endpoint lives under `/gateway/v1`, outside every BIAN domain, and is not public.
- **Off by default** (`GATEWAY_INVESTIGATION_ENABLED`): switched off, it is simply not there (404, the gateway never reveals what exists).
- **The reason is mandatory** (10 to 200 characters) and must carry no personal data (no email, no long number): refer to a ticket id instead. It is checked before anything is recorded.
- **Recorded before it is disclosed, and fail-closed.** The gateway appends the lookup to the ledger - actor (the investigator), target pseudonym and reason, never the email - and waits for the ledger to accept it before it answers. If the ledger is down or refuses, nothing is disclosed (502 `ERR-GTW-00502`). This is the one place the gateway does not treat the ledger as best effort (ADR-023): an unrecorded lookup would defeat the point.
- **Limited:** each investigator gets `GATEWAY_INVESTIGATION_MAX_PER_HOUR` lookups an hour (default 20; a token bucket), then 429 `ERR-GTW-00429`.
- Without a configured pseudonymisation key there is nothing to compute (409 `ERR-GTW-00409`).
- The generic audit filter does not describe the gateway's own endpoints; this one records itself, with more detail.

## Consequences
- Positive: an investigation has a procedure that adds no personal data to the ledger, is itself auditable (who looked up whom, when, why), and cannot be done in secret.
- Negative: only people with a known email can be traced (a sign-in under a different email, or an entry that carries a user id instead, is another lookup); destroying the key (erasure) makes the lookup answer a pseudonym that matches nothing recorded before - which is the intent. The investigator's own identity is the token subject; nothing here verifies the reason is true.
- See `docs/runbook-investigation.md` for the steps an investigator follows.

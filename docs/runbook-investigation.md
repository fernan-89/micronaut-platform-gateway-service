# Runbook: investigating what a known person did

For an administrator who has a person's email and a reason to look (a ticket, an incident). It uses the platform's own records and adds no personal data to them. See ADR-024 (why sign-ins carry a pseudonym) and ADR-027 (this lookup).

## Before you start
- You need the ADMIN role for the organisation, and `GATEWAY_INVESTIGATION_ENABLED=true` on the gateway.
- Have a ticket id. The reason you give is stored on the ledger forever: write the ticket id and a few plain words. **Do not write names, emails or long numbers in it** - the request is refused if you do.

## Steps
1. **Ask for the pseudonym** (the gateway records your request first):
   ```
   POST /api/gateway/v1/investigation/pseudonym
   X-Tenant-Id: <organisation id>     (from your session)
   { "email": "person@example.com", "reason": "Ticket SEC-1234: unusual night access" }
   ```
   Answer: `pseudonym`, `actor` (`login:<hmac>`) and `ledgerQuery`.
2. **Read the ledger by that actor** for the same organisation:
   `GET /api/compliance-audit-ledger/v1/retrieve?actor=login:<hmac>&limit=100`
   This lists that person's sign-in attempts and their outcome (`status=...`). Entries made *as* the person after they signed in carry their user id (the token subject) as actor, not the email: read the sign-in entries, then search the ledger by the user id for the rest (the party directory resolves a user id to a person, under its own access control).
3. **Verify the chain** if the evidence matters: `GET /api/compliance-audit-ledger/v1/integrity-check/evaluate` must say valid, with anchors verified (ADR-034 of the ledger).
4. **Record the outcome in your ticket** (not in the platform): what you looked at and why. Your lookup is already on the ledger: `GET /compliance-audit-ledger/v1/retrieve?actor=<your user id>` shows every lookup you made, with its target pseudonym and reason.

## What you cannot do, by design
- Turn a pseudonym back into a person. There is no such table. If you only have a pseudonym, you can read what it did, not who it is.
- Look people up in secret. If the ledger cannot record your lookup, you get no answer.
- Look up more than the hourly allowance (default 20).

## Erasure
Destroying the pseudonymisation key makes every pseudonym ever issued unlinkable to a person: the lookup then answers a pseudonym that matches nothing recorded earlier. Do it only for a documented erasure request, and record it in the ticket.

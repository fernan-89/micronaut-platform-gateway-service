# ADR-024: Audit Recording Is Data-Minimised (PCI / LGPD / HIPAA Discipline)

## Status
Accepted (extends ADR-023; the ledger side is ledger ADR-033)

## Context
The gateway sees everything: bodies with passwords, bearer tokens, query strings with search terms, emails. Recording the
requests it forwards onto an immutable ledger is only acceptable if what it records is chosen by an allow-list, not by
subtraction.

## Decision
- **Allow-list, not block-list.** An audit entry is built from exactly: tenant (header), actor, `METHOD` plus the path with every
  UUID masked as `{id}`, the Service Domain, the first UUID in the path, and `status=NNN`. Nothing else is read into an entry: not
  the query string, not the body, not any header other than `X-Tenant-Id` and `X-Executor`, never `Authorization`.
- **Sign-in is recorded, minimally.** Sign-in carries its tenant only in the body, so it is read in the controller (after the
  upstream answered): of that body only `organisationId` is used, and the email survives solely as `login:<keyed pseudonym>`
  (HMAC-SHA256, `gateway.audit.pseudonym-key`). **The password is never read into an entry, logged or forwarded to the ledger.**
  A body that cannot be parsed is dropped, and its parse error is deliberately neither logged nor kept, because such a message can
  quote the very text that must not be retained. The result is `POST /party-authentication/v1/session/initiate`, `status=200`
  or `401`, which is enough to see brute-force attempts against a tenant without recording who tried or with what.
- **An executor that is an email is pseudonymised too** (`user:<hex>`). A signed-in person is identified by the opaque token
  subject, never by their address; the web app sends the subject as `X-Executor`.
- **No key, no hash.** Without a configured key the pseudonym is the literal `unkeyed`: an unkeyed hash of an email can be
  recovered by brute force and would only look like protection.
- The ledger independently refuses emails, card numbers, tokens and credentials (ledger ADR-033), so a bug here fails closed at
  the ledger instead of leaking into the chain.
- The tests assert the negative: a password, an email, a bearer token, a query-string value and a body field never appear anywhere
  in what is sent to the ledger.

## Consequences
- Positive: the audit trail answers who (a pseudonym) did what (a masked call) to which resource, when, and how it ended - and
  cannot be turned into a credential or contact-data store.
- Negative: a deployment that sets no key gets `login:unkeyed`: attempts are visible but not attributable to a person.
- Negative: the gateway cannot know which path segments or query values are personal; it handles the cases it can recognise (UUIDs
  are masked, queries are dropped). A service that put personal data in a URL path segment would still have it recorded - the
  platform convention is opaque identifiers in paths, and the ledger guard catches an email or card number if one slips through.

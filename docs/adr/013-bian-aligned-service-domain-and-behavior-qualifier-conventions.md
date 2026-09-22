# ADR 013: BIAN-Aligned Service Domain and Behavior Qualifier Conventions

## Status
Accepted

## Context
The platform's two live services (Hash Token Registry and Party Reference Data Directory) had each evolved their own ad-hoc REST vocabulary — plain CRUD verbs on plural nouns (`/hashes`, `/api/v1/companies`), inconsistent placement of the API version segment, and no shared taxonomy for lifecycle transitions. The ThinkLab platform blueprint documents five more microservices still to be built (User/IAM, Route, Compute, Maintenance, License); shipping two more incompatible conventions would compound the fragmentation already observed across the Hash Service's competing "Convention A/B" collections and the four divergent User Service prototypes.

## Decision
Adopt a BIAN-style (Banking Industry Architecture Network) resource model platform-wide:
- Each microservice is a **Service Domain (SD)**, addressed by a kebab-case business-capability name as the first URL segment, followed by the version (`/{sd-name}/v1/...`).
- The primary aggregate is the SD's **Control Record (CR)**. Sub-entities with an independent lifecycle (e.g. `OrganisationUnit`) are **Behavior Qualifier Instance Records**, individually addressable under the parent CR.
- Every route ends in a standard **Behavior Qualifier**: `initiate` (POST, create), `retrieve` (GET, single/collection/search), `update` (PUT, non-lifecycle attributes), `control` (PUT, lifecycle/state transitions — `control/{action}`).
- `DELETE` is never used. Terminal states are reached through a `control` transition (e.g. `control/revoke`, `control/cancel`), never a physical deletion — preserving forensic/audit history.
- `X-Executor` is a mandatory header on every mutating call across all services (previously inconsistent: sometimes a body field, sometimes absent entirely — this service captured no executor at all). The Hash Token Registry additionally requires `X-Source-Service`; `X-Tenant-Id` remains mandatory on tenant-scoped retrieval.
- JSON payload field names stay pragmatic/market-standard (`id`, `status`, ...) — BIAN rigor is applied to URL structure and resource modeling, not to internal payload jargon.

This is the reference convention for the remaining five services in the blueprint. See ADR-014 for this service's specific rename, and ADR-015 for the OrganisationUnit addressability change.

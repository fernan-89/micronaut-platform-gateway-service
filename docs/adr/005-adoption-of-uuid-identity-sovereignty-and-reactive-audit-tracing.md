# ADR 005: Sovereign Identity (UUID) & Reactive Tracing

## Status
Accepted

## Context
Relying on database-generated auto-increment IDs or unstructured string keys compromises distributed traceability and aggregate identity across microservice meshes.

## Decision
All primary entities (Companies, Branches, Contacts) must use cryptographically sound `java.util.UUID` identifiers generated via the sovereign `HashServicePort`. MongoDB documents store standard BSON Subtype 4 UUIDs with `uuid-representation: STANDARD`.

# ADR 001: Adoption of Hexagonal Architecture with Reactive Stack

## Status
Accepted

## Context
The Company Service requires a robust, testable, and scalable architecture capable of handling high-throughput asynchronous operations with high assurance. Monolithic or tightly coupled architectures introduce risks to maintenance and testability.

## Decision
We implement **Hexagonal Architecture (Ports and Adapters)** combined with a **Reactive Stack** (Micronaut 4, Project Reactor/Netty, and MongoDB Reactive Streams).

### Key Components:
1. **Domain Layer:** Pure Java records and entities representing business rules and invariants, free of framework annotations.
2. **Application Layer:** Orchestration and Use Cases managing business workflows, isolated via DTOs and mappers.
3. **Infrastructure Layer:** Inbound REST controllers, reactive MongoDB adapters, telemetry filters, and health indicators.

## Consequences
- **Positive:** Complete domain decoupling, non-blocking high-throughput I/O, 100% unit testability without external dependencies.
- **Negative:** Increased initial mapping code and discipline required for DTO boundary transformations.

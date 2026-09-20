# Architecture decisions

## ADR-001: Keep Sentinel outside the auction server

**Decision:** ship Sentinel as a separate Ktor service.

**Why:** Apex Ad Server's Go request path is latency-sensitive. Policy authoring, audit queries, SSE, and incident analysis have different scaling and release characteristics. A separate control plane can fail or deploy independently. Any backend can adopt the HTTP contract.

**Cost:** one network hop when synchronous enforcement is enabled. Apex should use a short timeout, bounded retry policy, and an explicit fail-open or fail-closed rule per event type. For auctions, prefer local cached policy or shadow/asynchronous observation until latency and availability targets are proven.

## ADR-002: Deterministic enforcement, optional AI explanation

**Decision:** only `PolicyEngine` produces enforcement decisions. `IncidentAnalyst` is post-decision and advisory.

**Why:** traffic and billing decisions must be reproducible, explainable, testable, and safe during model/provider outages.

**Cost:** hand-authored rules evolve more slowly than model behavior. The remedy is versioned policy tooling, good evidence, and review—not hidden model authority.

## ADR-003: PostgreSQL as production source of truth

**Decision:** production startup rejects memory storage. Flyway owns schema changes.

**Why:** idempotency, tenant isolation, and audit history require durable transactional state. PostgreSQL provides a unique constraint as the final arbiter during concurrent retries.

**Cost:** JDBC calls block. They run on `Dispatchers.IO`, the pool is bounded, and readiness checks reveal database failure.

## ADR-004: Explicit wiring, no dependency-injection framework

**Decision:** constructors plus the Ktor `module()` composition root.

**Why:** the dependency graph is small, visible, and easy to override in tests. A DI container would add lifecycle and discovery behavior without enough payoff.

**Revisit when:** many modules need scoped lifetimes, conditional bindings, or a much larger graph.

## ADR-005: Bearer API keys for service authentication

**Decision:** map bearer secrets to tenants through runtime configuration, compare in constant time, and never log raw values.

**Why:** this is sufficient for a portfolio and internal service-to-service deployment.

**Production evolution:** use workload identity or OAuth2 client credentials, rotate keys through a secret manager, store only hashes, and put TLS everywhere. Authorization remains tenant-scoped even after authentication changes.

## ADR-006: At-least-once clients with strict idempotency

**Decision:** retries are expected. A key is bound to one request body and one stored result.

**Why:** networks fail after a server commits but before a client receives the response. Strict key/body binding prevents an accidental key reuse from returning an unrelated result.

## Failure-mode table

| Failure | Behavior | Operator signal |
|---|---|---|
| Invalid/missing key | `401` | access logs; no secret echoed |
| Bad request | `400` stable JSON envelope | request ID |
| Changed body under same idempotency key | `409` | idempotency counter can be added |
| Rate limit depleted | `429` plus `Retry-After` | response headers |
| PostgreSQL unavailable | readiness `503`; request fails safely | health probe and structured error log |
| SSE subscriber disconnects | coroutine collection cancels | normal connection closure |
| Incident analyst unavailable in a future AI adapter | fall back to deterministic analyst | adapter-specific metric |

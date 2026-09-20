# Learn Ktor through Apex Sentinel

This guide assumes you know basic programming but are new to backend development and Ktor. Read it with the code open; every concept points to a real part of the service.

## 1. The mental model

An HTTP backend repeats one loop:

1. accept a request;
2. establish who sent it;
3. reject malformed or excessive traffic;
4. run business logic;
5. persist the result;
6. return an HTTP response and enough telemetry to operate the service.

Ktor models that loop as a pipeline. Plugins add behavior around route handlers. In `Application.kt`, `CallId` runs before your route so every response and log can share a request ID. `Authentication` turns a bearer token into a tenant principal. `ContentNegotiation` converts JSON into Kotlin objects. `StatusPages` turns exceptions into a stable error contract.

The important lesson is that a plugin handles a cross-cutting concern, while a route should mostly translate HTTP into an application call.

## 2. Follow one request

Open these files in order:

1. `api/Routing.kt` receives `POST /v1/evaluations`.
2. `application/SentinelService.kt` checks the idempotency key and orchestrates the use case.
3. `domain/PolicyEngine.kt` evaluates a pure policy with no HTTP or SQL knowledge.
4. `application/Ports.kt` defines what persistence must do.
5. `infrastructure/PostgresSentinelStore.kt` implements that contract with JDBC.

That separation is called ports and adapters (or hexagonal architecture). It is not ceremony for its own sake. The policy unit tests run without starting a server or database, while API tests can swap PostgreSQL for the in-memory adapter.

## 3. Kotlin concepts used here

### Data classes and immutability

`EvaluationRequest`, `PolicyDocument`, and `EvaluationResult` are data classes whose properties are `val`. They are value objects: easy to compare, serialize, and reason about. `copy(replayed = true)` creates a changed value without mutating the stored decision.

### Null safety

Optional fields, such as `country`, use `String?`. Kotlin forces code to consider absence. Route parameters use `?: throw InvalidRequest(...)` so a missing value becomes an intentional client error instead of a later null-pointer failure.

### Extension functions

`Application.configureRouting(...)` adds a function to Ktor's `Application` type. The private `ApplicationCall.respondError(...)` extension keeps the error envelope consistent.

### Coroutines

Ktor route handlers are suspending functions. A request does not need to hold a platform thread while it waits. JDBC is blocking, so the PostgreSQL adapter explicitly moves calls to `Dispatchers.IO` with `withContext`. This boundary matters: pretending blocking JDBC is non-blocking can starve request threads.

### Flow

`SharedFlowEvaluationEvents` is a hot stream. Each new evaluation is published once; SSE subscribers collect only their tenant's events. `Flow` supplies cancellation automatically when a client disconnects.

## 4. Ktor concepts used here

### Explicit application assembly

`module()` is the composition root. It chooses adapters and installs plugins. There is no classpath scanning and no hidden dependency-injection container. Constructor injection keeps dependencies visible.

### Plugins

Ktor plugins intercept phases of the request pipeline. This project uses standard plugins and includes its own in `ktor-guardrails/TenantRateLimit.kt`. That module is reusable from another Ktor service:

```kotlin
install(TenantRateLimit) {
    capacity = 500
    refillPerSecond = 20.0
    key = { call -> call.request.headers["X-Customer-Id"] ?: "anonymous" }
}
```

The token bucket allows a short burst up to `capacity`, then restores tokens at a steady rate. Tenant keys are isolated. In this app, the actual bearer value is hashed before it becomes an in-memory key.

### Test host

`testApplication` runs the full Ktor pipeline without binding a real network port. The tests verify headers, authentication, serialization, routes, exception mapping, and tenant isolation quickly and deterministically.

## 5. Why idempotency is not caching

Clients retry when connections time out. Without idempotency, a retry can create a second decision or side effect. The client supplies `Idempotency-Key`; the database owns a unique `(tenant_id, idempotency_key)` constraint.

- Same key and same request: return the original result with `replayed: true`.
- Same key and different request: return `409 Conflict`.
- Different key: create a new evaluation.

The database constraint, not an in-memory check, is the final race arbiter. This is the pattern to use for payments, job submission, and webhook ingestion too.

## 6. Why AI does not block traffic

The `IncidentAnalyst` port summarizes evidence after deterministic decisions are stored. The current adapter is deterministic and testable. A future LLM adapter may produce a richer narrative, but it should receive minimized evidence and its output must remain advisory.

This is a good production boundary for agentic AI:

- deterministic code enforces policy;
- tools retrieve bounded evidence;
- AI explains or proposes;
- a human or versioned policy change authorizes new enforcement.

Never make a probabilistic model the unaudited gate for billing, security, or user access.

## 7. Important trade-offs

### JDBC instead of a reactive database driver

JDBC is mature, observable, and easy for teams to operate. `Dispatchers.IO` contains its blocking behavior. R2DBC can improve efficiency for workloads dominated by many slow database waits, but adds driver and transaction complexity. Measure before changing.

### SQL instead of an ORM

The service has a small schema and important concurrency semantics. Explicit SQL makes the unique idempotency constraint, advisory lock, and query shape obvious. An ORM becomes attractive when the domain has many relationships and repetitive mapping.

### In-process SSE and rate limits

These are excellent for one instance and local learning. Multiple replicas need shared infrastructure: Redis for a global token bucket and Kafka/Redpanda or PostgreSQL LISTEN/NOTIFY for cross-node events. The interfaces make that a replacement, not a rewrite.

### Rule sum instead of first-match

Multiple weak signals can become meaningful together. Scores are summed and clamped to 100. This is explainable, but correlated rules can double-count risk; policy review must check that. A first-match policy is simpler but loses combination behavior.

## 8. Ktor vs Go vs Spring Boot

There is no universal winner.

| Concern | Ktor | Go `net/http` ecosystem | Spring Boot |
|---|---|---|---|
| Startup and footprint | Lean JVM service; only selected plugins | Usually best | Usually heaviest, though AOT/native can help |
| Concurrency model | Structured coroutines and Flow | Goroutines and channels are exceptionally simple | MVC, virtual threads, or reactive stacks; more choices |
| Type-rich domain code | Excellent Kotlin expressiveness and null safety | Simple and explicit, but less expressive sum/value modeling | Excellent Java/Kotlin domain support |
| Framework behavior | Explicit and composable | Very explicit, library-driven | Convention-rich and highly automated |
| Ecosystem | Full JVM ecosystem, smaller Ktor-specific ecosystem | Strong cloud/network tooling | Broadest enterprise integration ecosystem |
| Compile/deploy loop | Slower than Go; JVM image larger | Fast compile, single small binary | Similar JVM costs, usually more framework work at startup |
| Best fit here | Policy/control plane with Kotlin domain logic and streaming | Auction/data-plane hot path with tight resource goals | Large enterprise platform needing mature integrations and team conventions |

Where this service beats a typical Go implementation is domain expressiveness, reuse of JVM libraries, and the ergonomics of coroutine composition when workflows become rich. Where it beats typical Spring Boot is transparency: the application pipeline and wiring fit in a small number of files, startup is leaner, and you opt into features rather than inheriting a large auto-configured platform.

Go still wins when tiny binaries, very low memory, fast cold starts, and simple high-throughput network services dominate. Spring Boot wins when an organization needs its huge integration catalog, standardized conventions, Spring Security depth, and a large hiring pool. Staff-level engineering means selecting based on constraints, not language loyalty.

## 9. Exercises that will make you competent

Do these in order:

1. Add a `LESS_THAN_OR_EQUAL` operator and tests.
2. Add cursor pagination to a tenant's evaluation history.
3. Create a Redis `BucketStore` and prove two app replicas share a limit.
4. Add Testcontainers and run the real migration/idempotency tests against PostgreSQL.
5. Publish `ktor-guardrails` to a local Maven repository and consume it from a second Ktor app.
6. Add an outbox table so evaluation events survive a process crash.
7. Benchmark the policy engine separately from HTTP and database latency.
8. Write an LLM-backed `IncidentAnalyst` that outputs a typed schema, redacts identifiers, has a timeout, and falls back to the deterministic analyst.

After each exercise, state the invariant first, then write a failing test, then implement. That habit matters more than memorizing Ktor APIs.

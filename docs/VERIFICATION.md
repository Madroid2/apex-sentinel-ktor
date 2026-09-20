# Verification record

This file distinguishes executed evidence from architectural intent. It should be updated whenever a release changes important behavior.

## Verified locally on 2026-09-20

Environment:

- Apple Silicon macOS
- Java 21.0.10
- Gradle 8.14.4
- Docker Engine 29.6.1
- Docker Compose 5.1.4
- PostgreSQL 17.11 container from `postgres:17-alpine`

Executed checks:

| Check | Evidence |
|---|---|
| Unit and Ktor API suite | 9 tests, 0 failures |
| Executable packaging | `:app:buildFatJar` completed successfully |
| Compose validation | `docker compose config --quiet` completed successfully |
| Clean container build | Multi-stage image built successfully from clean JDK/JRE base images and produced `apex-sentinel-ktor-sentinel:latest` |
| Memory-mode readiness | `/health/ready` returned `UP` |
| Evaluation behavior | New request returned `201 BLOCK` with matched-rule evidence |
| Idempotent replay | Identical request/key returned `200`, the same ID, and `replayed: true` |
| Incident analysis | Brief contained the stored block and leading reason |
| PostgreSQL migration | Flyway validated and applied `V1__sentinel_schema.sql` |
| PostgreSQL audit write | One tenant-scoped `BLOCK` row was persisted |
| Restart durability | The service restarted, Flyway reported schema v1 current, and the stored evaluation returned `200` by ID |
| Graceful shutdown | Ktor shutdown closed the Hikari pool cleanly |

The PostgreSQL test container was stopped after verification. Its named Compose volume was preserved for future local runs.

## Not yet verified

The following must not be represented as completed production evidence:

- multi-replica behavior;
- sustained load, latency percentiles, or final pool sizing;
- regional failover and database restore;
- Redis-backed global rate limiting;
- durable event-broker or transactional-outbox delivery;
- production OAuth/workload identity;
- vulnerability scanning, image signing, or SBOM publication;
- a real Apex shadow-traffic rollout;
- live Google Play Integrity T2/T3 activation.

For the last item, Apex Ad Server's `docs/PRODUCTION_TRUST_ACTIVATION.md` remains the canonical acceptance gate.

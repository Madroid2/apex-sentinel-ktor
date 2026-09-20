# Production checklist

- Run with Java 21 and `SENTINEL_ENV=production`.
- Use `SENTINEL_STORAGE=postgres` and managed PostgreSQL with encrypted connections, backups, and tested restore.
- Put tenant and admin credentials in a secret manager; use separate long random values and rotate them.
- Terminate TLS at a trusted ingress and accept bearer credentials only over HTTPS.
- Restrict `/metrics`, admin routes, and API documentation at the network or gateway layer.
- Set CPU/memory requests and limits; preserve the JVM's container awareness.
- Use liveness for process restart and readiness for traffic removal.
- Alert on readiness failure, `5xx`, sustained `429`, pool exhaustion, and unusual decision shifts.
- Send structured logs to a central store and retain request IDs. Redact authorization headers and subject identifiers.
- Replace the process-local bucket store with Redis when running multiple replicas and a global tenant limit is required.
- Replace process-local SSE fan-out with a durable broker or PostgreSQL-backed notification when every node must see every event.
- Add PostgreSQL integration tests through Testcontainers in an environment with Docker.
- Load test with realistic policy sizes and database latency before choosing timeouts or pool sizes.
- Backward-check OpenAPI changes and use expand/migrate/contract database releases.
- Decide and document fail-open versus fail-closed behavior for every caller and event type.
- Do not claim Apex T2/T3 trust activation from these tests; complete Apex's live Play Integrity runbook.

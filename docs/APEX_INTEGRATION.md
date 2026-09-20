# Integrating Apex Ad Server

Start in **shadow mode**. Send a privacy-minimized observation after the current Apex traffic-quality evaluation and record Sentinel's proposed decision without changing auction routing. Compare decisions and latency before enforcement.

## Request mapping

| Apex concept | Sentinel field |
|---|---|
| auction/request ID | `eventId` |
| `auction`, `bill`, `impression`, `click` | `eventType` |
| short-lived HMAC of the device/request key | `subjectId` |
| Apex trust tier T0–T3 | `signals.trustTier` 0–3 |
| bounded velocity window | `signals.requestsPerMinute` |
| network reputation score | `signals.ipRisk` 0–1 |
| decoded integrity class | `signals.deviceIntegrity` |
| advisory emulator signal | `signals.emulator` |

Do not send raw Play Integrity tokens, advertising IDs, IP addresses, publisher secrets, or lease secrets. Sentinel only needs minimized decision evidence.

## Go caller sketch

Use the auction ID as part of a stable idempotency key. Configure a strict timeout shorter than the caller's remaining budget.

```go
ctx, cancel := context.WithTimeout(parent, 20*time.Millisecond)
defer cancel()

req, err := http.NewRequestWithContext(ctx, http.MethodPost, sentinelURL+"/v1/evaluations", body)
if err != nil { /* record shadow failure */ }
req.Header.Set("Authorization", "Bearer "+sentinelAPIKey)
req.Header.Set("Idempotency-Key", "auction:"+auctionID)
req.Header.Set("Content-Type", "application/json")
```

Keep the secret in the runtime secret store, not `config.yaml`. Never log the authorization header.

## Rollout stages

1. **Shadow:** async send, measure decision distribution, error rate, and p50/p95/p99 latency.
2. **Read-only operator use:** incident briefs and SSE inform investigations.
3. **Selective synchronous review:** only high-value event types call Sentinel inline; timeout follows an explicit fallback.
4. **Enforcement:** activate a reviewed policy version for a small tenant cohort, then expand.

For the Apex trust layer, Sentinel evidence does not replace the canonical real-token acceptance gate in Apex Ad Server's `docs/PRODUCTION_TRUST_ACTIVATION.md`. Emulator telemetry remains advisory; cryptographic trust comes from the verified Play Integrity and signed-envelope path.

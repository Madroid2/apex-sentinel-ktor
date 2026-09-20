package com.apex.sentinel.application

import com.apex.sentinel.domain.EvaluationRequest
import com.apex.sentinel.domain.EvaluationResult
import com.apex.sentinel.domain.IncidentBrief
import com.apex.sentinel.domain.PolicyDocument
import com.apex.sentinel.domain.PolicyEngine
import com.apex.sentinel.domain.PolicyValidationResult
import com.apex.sentinel.domain.ReasonCount
import com.apex.sentinel.domain.defaultPolicy
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

class SentinelService(
    private val store: SentinelStore,
    private val engine: PolicyEngine,
    private val events: EvaluationEvents,
    private val analyst: IncidentAnalyst,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun evaluate(tenantId: String, idempotencyKey: String, request: EvaluationRequest): EvaluationResult {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 200) {
            throw InvalidRequest("Idempotency-Key must contain 1 to 200 characters")
        }
        val policy = store.activePolicy(tenantId) ?: defaultPolicy()
        val candidate = engine.evaluate(request, policy)
        val (stored, replayed) = store.saveOrGet(StoredEvaluation(tenantId, idempotencyKey, request, candidate))
        if (!replayed) events.publish(tenantId, stored)
        return stored.copy(replayed = replayed)
    }

    suspend fun get(tenantId: String, id: String): EvaluationResult =
        store.findEvaluation(tenantId, id) ?: throw EvaluationNotFound(id)

    fun validatePolicy(policy: PolicyDocument): PolicyValidationResult = engine.validate(policy)

    suspend fun activatePolicy(tenantId: String, policy: PolicyDocument): PolicyDocument {
        val validation = engine.validate(policy)
        if (!validation.valid) throw InvalidRequest(validation.errors.joinToString("; "))
        return store.activatePolicy(tenantId, policy)
    }

    suspend fun incidentBrief(tenantId: String, lookbackMinutes: Int): IncidentBrief {
        if (lookbackMinutes !in 1..10_080) throw InvalidRequest("lookbackMinutes must be between 1 and 10080")
        val since = Instant.now(clock).minus(lookbackMinutes.toLong(), ChronoUnit.MINUTES)
        return analyst.createBrief(store.flaggedSince(tenantId, since, 1_000), lookbackMinutes)
    }
}

class DeterministicIncidentAnalyst(
    private val clock: Clock = Clock.systemUTC(),
) : IncidentAnalyst {
    override fun createBrief(results: List<EvaluationResult>, lookbackMinutes: Int): IncidentBrief {
        val reasonCounts = results.flatMap { it.reasons }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5)
            .map { ReasonCount(it.key, it.value) }
        val blocked = results.count { it.decision == com.apex.sentinel.domain.Decision.BLOCK }
        val reviewed = results.count { it.decision == com.apex.sentinel.domain.Decision.REVIEW }
        val leading = reasonCounts.firstOrNull()?.reason ?: "none"
        val summary = if (results.isEmpty()) {
            "No flagged traffic was observed in the selected window."
        } else {
            "$blocked events were blocked and $reviewed require review; the leading signal was $leading."
        }
        val actions = buildList {
            if (reasonCounts.any { it.reason == "untrusted_request_burst" }) {
                add("Inspect the affected publisher and request-key velocity before widening demand access.")
            }
            if (reasonCounts.any { it.reason == "device_integrity_failed" }) {
                add("Compare failed integrity traffic with Play-delivered release and certificate evidence.")
            }
            if (reasonCounts.any { it.reason == "high_ip_risk" }) {
                add("Sample network reputation evidence and confirm false-positive rates before blocking broadly.")
            }
            if (isEmpty()) add("Keep the policy unchanged and continue monitoring.")
        }
        return IncidentBrief(
            generatedAt = Instant.now(clock).toString(),
            lookbackMinutes = lookbackMinutes,
            totalFlagged = results.size,
            blocked = blocked,
            underReview = reviewed,
            topReasons = reasonCounts,
            summary = summary,
            recommendedActions = actions,
        )
    }
}

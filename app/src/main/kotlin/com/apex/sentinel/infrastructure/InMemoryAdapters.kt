package com.apex.sentinel.infrastructure

import com.apex.sentinel.application.EvaluationEvents
import com.apex.sentinel.application.IdempotencyConflict
import com.apex.sentinel.application.SentinelStore
import com.apex.sentinel.application.StoredEvaluation
import com.apex.sentinel.application.VersionConflict
import com.apex.sentinel.domain.EvaluationResult
import com.apex.sentinel.domain.PolicyDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemorySentinelStore : SentinelStore {
    private val policies = ConcurrentHashMap<String, PolicyDocument>()
    private val evaluations = ConcurrentHashMap<String, StoredEvaluation>()
    private val idempotency = ConcurrentHashMap<String, String>()

    override suspend fun activePolicy(tenantId: String): PolicyDocument? = policies[tenantId]

    override suspend fun activatePolicy(tenantId: String, policy: PolicyDocument): PolicyDocument {
        policies.compute(tenantId) { _, current ->
            if (current != null && policy.version <= current.version) {
                throw VersionConflict("policy version must be greater than active version ${current.version}")
            }
            policy
        }
        return policy
    }

    override suspend fun saveOrGet(evaluation: StoredEvaluation): Pair<EvaluationResult, Boolean> = synchronized(this) {
        val key = "${evaluation.tenantId}:${evaluation.idempotencyKey}"
        val existingId = idempotency.putIfAbsent(key, evaluation.result.id)
        if (existingId != null) {
            val existing = evaluations.getValue("${evaluation.tenantId}:$existingId")
            if (existing.request != evaluation.request) {
                throw IdempotencyConflict("Idempotency-Key was already used with a different request")
            }
            return existing.result to true
        }
        evaluations["${evaluation.tenantId}:${evaluation.result.id}"] = evaluation
        evaluation.result to false
    }

    override suspend fun findEvaluation(tenantId: String, id: String): EvaluationResult? =
        evaluations["$tenantId:$id"]?.result

    override suspend fun flaggedSince(tenantId: String, since: Instant, limit: Int): List<EvaluationResult> =
        evaluations.values.asSequence()
            .filter { it.tenantId == tenantId }
            .map { it.result }
            .filter { it.decision != com.apex.sentinel.domain.Decision.ACCEPT }
            .filter { Instant.parse(it.evaluatedAt) >= since }
            .sortedByDescending { it.evaluatedAt }
            .take(limit)
            .toList()

    override suspend fun isReady(): Boolean = true
}

class SharedFlowEvaluationEvents : EvaluationEvents {
    private data class TenantEvent(val tenantId: String, val result: EvaluationResult)
    private val events = MutableSharedFlow<TenantEvent>(extraBufferCapacity = 1_024)

    override suspend fun publish(tenantId: String, result: EvaluationResult) {
        events.emit(TenantEvent(tenantId, result))
    }

    override fun stream(tenantId: String): Flow<EvaluationResult> =
        events.filter { it.tenantId == tenantId }.map { it.result }
}

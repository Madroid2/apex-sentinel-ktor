package com.apex.sentinel.application

import com.apex.sentinel.domain.Decision
import com.apex.sentinel.domain.EvaluationRequest
import com.apex.sentinel.domain.EvaluationResult
import com.apex.sentinel.domain.PolicyDocument
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class StoredEvaluation(
    val tenantId: String,
    val idempotencyKey: String,
    val request: EvaluationRequest,
    val result: EvaluationResult,
)

interface SentinelStore : AutoCloseable {
    suspend fun activePolicy(tenantId: String): PolicyDocument?
    suspend fun activatePolicy(tenantId: String, policy: PolicyDocument): PolicyDocument
    suspend fun saveOrGet(evaluation: StoredEvaluation): Pair<EvaluationResult, Boolean>
    suspend fun findEvaluation(tenantId: String, id: String): EvaluationResult?
    suspend fun flaggedSince(tenantId: String, since: Instant, limit: Int): List<EvaluationResult>
    suspend fun isReady(): Boolean
    override fun close() = Unit
}

interface EvaluationEvents {
    suspend fun publish(tenantId: String, result: EvaluationResult)
    fun stream(tenantId: String): Flow<EvaluationResult>
}

interface IncidentAnalyst {
    fun createBrief(results: List<EvaluationResult>, lookbackMinutes: Int): com.apex.sentinel.domain.IncidentBrief
}

data class TenantPrincipal(val tenantId: String, val role: Role)

enum class Role { TENANT, ADMIN }

class EvaluationNotFound(id: String) : RuntimeException("evaluation $id was not found")
class VersionConflict(message: String) : RuntimeException(message)
class IdempotencyConflict(message: String) : RuntimeException(message)
class InvalidRequest(message: String) : RuntimeException(message)

fun EvaluationResult.isFlagged() = decision != Decision.ACCEPT

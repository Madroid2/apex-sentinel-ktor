package com.apex.sentinel.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Decision { ACCEPT, REVIEW, BLOCK }

@Serializable
enum class RuleOperator { EQUALS, GREATER_THAN_OR_EQUAL, LESS_THAN, IN }

@Serializable
data class RiskSignals(
    val trustTier: Int,
    val requestsPerMinute: Int,
    val ipRisk: Double,
    val deviceIntegrity: String,
    val emulator: Boolean = false,
    val country: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

@Serializable
data class EvaluationRequest(
    val eventId: String,
    val eventType: String,
    val subjectId: String,
    val occurredAt: String? = null,
    val signals: RiskSignals,
)

@Serializable
data class PolicyCondition(
    val field: String,
    val operator: RuleOperator,
    val value: String,
)

@Serializable
data class PolicyRule(
    val id: String,
    val description: String,
    val all: List<PolicyCondition>,
    val score: Int,
    val reason: String,
)

@Serializable
data class PolicyDocument(
    val version: Int,
    val reviewAt: Int = 40,
    val blockAt: Int = 80,
    val rules: List<PolicyRule>,
)

@Serializable
data class MatchedRule(
    val ruleId: String,
    val description: String,
    val score: Int,
)

@Serializable
data class EvaluationResult(
    val id: String,
    val eventId: String,
    val decision: Decision,
    val score: Int,
    val reasons: List<String>,
    val matchedRules: List<MatchedRule>,
    val policyVersion: Int,
    val evaluatedAt: String,
    val replayed: Boolean = false,
)

@Serializable
data class PolicyValidationResult(
    val valid: Boolean,
    val errors: List<String>,
)

@Serializable
data class IncidentBrief(
    val generatedAt: String,
    val lookbackMinutes: Int,
    val totalFlagged: Int,
    val blocked: Int,
    val underReview: Int,
    val topReasons: List<ReasonCount>,
    val summary: String,
    val recommendedActions: List<String>,
    val generatedBy: String = "deterministic-incident-analyst-v1",
)

@Serializable
data class ReasonCount(val reason: String, val count: Int)

fun defaultPolicy() = PolicyDocument(
    version = 1,
    reviewAt = 40,
    blockAt = 80,
    rules = listOf(
        PolicyRule(
            id = "untrusted-burst",
            description = "Untrusted traffic cannot generate a request burst",
            all = listOf(
                PolicyCondition("trustTier", RuleOperator.LESS_THAN, "1"),
                PolicyCondition("requestsPerMinute", RuleOperator.GREATER_THAN_OR_EQUAL, "120"),
            ),
            score = 90,
            reason = "untrusted_request_burst",
        ),
        PolicyRule(
            id = "invalid-integrity",
            description = "Invalid device integrity is a strong risk signal",
            all = listOf(PolicyCondition("deviceIntegrity", RuleOperator.EQUALS, "FAILED")),
            score = 80,
            reason = "device_integrity_failed",
        ),
        PolicyRule(
            id = "risky-network",
            description = "High IP reputation risk requires review",
            all = listOf(PolicyCondition("ipRisk", RuleOperator.GREATER_THAN_OR_EQUAL, "0.75")),
            score = 45,
            reason = "high_ip_risk",
        ),
        PolicyRule(
            id = "emulator-observation",
            description = "Emulator traffic is reviewed, never treated as cryptographic proof",
            all = listOf(PolicyCondition("emulator", RuleOperator.EQUALS, "true")),
            score = 40,
            reason = "emulator_observed",
        ),
    ),
)

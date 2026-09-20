package com.apex.sentinel.domain

import java.time.Clock
import java.time.Instant
import java.util.UUID

class InvalidPolicyException(val problems: List<String>) : IllegalArgumentException(problems.joinToString())

class PolicyEngine(private val clock: Clock = Clock.systemUTC()) {
    fun validate(policy: PolicyDocument): PolicyValidationResult {
        val errors = buildList {
            if (policy.version < 1) add("version must be positive")
            if (policy.reviewAt !in 0..100) add("reviewAt must be between 0 and 100")
            if (policy.blockAt !in 1..100) add("blockAt must be between 1 and 100")
            if (policy.reviewAt >= policy.blockAt) add("reviewAt must be lower than blockAt")
            if (policy.rules.isEmpty()) add("at least one rule is required")
            val duplicates = policy.rules.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) add("rule ids must be unique: ${duplicates.sorted().joinToString()}")
            policy.rules.forEach { rule ->
                if (rule.id.isBlank()) add("rule id cannot be blank")
                if (rule.all.isEmpty()) add("rule ${rule.id} must contain at least one condition")
                if (rule.score !in 1..100) add("rule ${rule.id} score must be between 1 and 100")
                rule.all.forEach { condition ->
                    if (condition.field !in supportedFields && !condition.field.startsWith("attributes.")) {
                        add("rule ${rule.id} uses unsupported field ${condition.field}")
                    }
                    if (condition.operator in numericOperators && condition.value.toDoubleOrNull() == null) {
                        add("rule ${rule.id} requires a numeric value for ${condition.operator}")
                    }
                }
            }
        }
        return PolicyValidationResult(errors.isEmpty(), errors)
    }

    fun evaluate(request: EvaluationRequest, policy: PolicyDocument): EvaluationResult {
        val validation = validate(policy)
        if (!validation.valid) throw InvalidPolicyException(validation.errors)

        val matched = policy.rules.filter { rule -> rule.all.all { matches(it, request.signals) } }
            .map { MatchedRule(it.id, it.description, it.score) }
        val score = matched.sumOf { it.score }.coerceAtMost(100)
        val decision = when {
            score >= policy.blockAt -> Decision.BLOCK
            score >= policy.reviewAt -> Decision.REVIEW
            else -> Decision.ACCEPT
        }

        return EvaluationResult(
            id = UUID.randomUUID().toString(),
            eventId = request.eventId,
            decision = decision,
            score = score,
            reasons = matched.mapNotNull { match -> policy.rules.firstOrNull { it.id == match.ruleId }?.reason }.distinct(),
            matchedRules = matched,
            policyVersion = policy.version,
            evaluatedAt = Instant.now(clock).toString(),
        )
    }

    private fun matches(condition: PolicyCondition, signals: RiskSignals): Boolean {
        val actual: Any? = when (condition.field) {
            "trustTier" -> signals.trustTier
            "requestsPerMinute" -> signals.requestsPerMinute
            "ipRisk" -> signals.ipRisk
            "deviceIntegrity" -> signals.deviceIntegrity
            "emulator" -> signals.emulator
            "country" -> signals.country
            else -> signals.attributes[condition.field.removePrefix("attributes.")]
        }

        return when (condition.operator) {
            RuleOperator.EQUALS -> actual?.toString()?.equals(condition.value, ignoreCase = true) == true
            RuleOperator.GREATER_THAN_OR_EQUAL -> actual.asDouble() >= condition.value.toDouble()
            RuleOperator.LESS_THAN -> actual.asDouble() < condition.value.toDouble()
            RuleOperator.IN -> condition.value.split(',').map(String::trim).any { candidate ->
                actual?.toString()?.equals(candidate, ignoreCase = true) == true
            }
        }
    }

    private fun Any?.asDouble(): Double = when (this) {
        is Number -> toDouble()
        else -> toString().toDoubleOrNull() ?: Double.NaN
    }

    private companion object {
        val supportedFields = setOf(
            "trustTier", "requestsPerMinute", "ipRisk", "deviceIntegrity", "emulator", "country",
        )
        val numericOperators = setOf(RuleOperator.GREATER_THAN_OR_EQUAL, RuleOperator.LESS_THAN)
    }
}

package com.apex.sentinel

import com.apex.sentinel.domain.Decision
import com.apex.sentinel.domain.EvaluationRequest
import com.apex.sentinel.domain.PolicyCondition
import com.apex.sentinel.domain.PolicyDocument
import com.apex.sentinel.domain.PolicyEngine
import com.apex.sentinel.domain.PolicyRule
import com.apex.sentinel.domain.RiskSignals
import com.apex.sentinel.domain.RuleOperator
import com.apex.sentinel.domain.defaultPolicy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PolicyEngineTest {
    private val engine = PolicyEngine(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

    @Test
    fun `blocks an untrusted request burst and explains why`() {
        val result = engine.evaluate(request(trustTier = 0, requestsPerMinute = 150), defaultPolicy())

        assertEquals(Decision.BLOCK, result.decision)
        assertEquals(90, result.score)
        assertEquals(listOf("untrusted_request_burst"), result.reasons)
        assertEquals("2026-01-01T00:00:00Z", result.evaluatedAt)
    }

    @Test
    fun `scores independent matching rules and clamps at one hundred`() {
        val result = engine.evaluate(
            request(trustTier = 0, requestsPerMinute = 150, ipRisk = 0.95, emulator = true),
            defaultPolicy(),
        )

        assertEquals(Decision.BLOCK, result.decision)
        assertEquals(100, result.score)
        assertEquals(3, result.matchedRules.size)
    }

    @Test
    fun `rejects a policy with unsafe thresholds`() {
        val invalid = PolicyDocument(
            version = 1,
            reviewAt = 90,
            blockAt = 80,
            rules = listOf(
                PolicyRule("x", "x", listOf(PolicyCondition("unknown", RuleOperator.EQUALS, "x")), 10, "x"),
            ),
        )

        val validation = engine.validate(invalid)
        assertFalse(validation.valid)
        assertEquals(2, validation.errors.size)
    }

    private fun request(
        trustTier: Int,
        requestsPerMinute: Int,
        ipRisk: Double = 0.1,
        emulator: Boolean = false,
    ) = EvaluationRequest(
        eventId = "evt-1",
        eventType = "auction",
        subjectId = "hashed-subject",
        signals = RiskSignals(trustTier, requestsPerMinute, ipRisk, "MEETS_DEVICE_INTEGRITY", emulator),
    )
}

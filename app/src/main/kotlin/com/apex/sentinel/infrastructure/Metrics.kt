package com.apex.sentinel.infrastructure

import com.apex.sentinel.domain.Decision
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

class Metrics {
    private val evaluations = ConcurrentHashMap<Decision, LongAdder>()
    private val replays = LongAdder()

    fun evaluation(decision: Decision, replayed: Boolean) {
        evaluations.computeIfAbsent(decision) { LongAdder() }.increment()
        if (replayed) replays.increment()
    }

    fun prometheus(): String = buildString {
        appendLine("# HELP sentinel_evaluations_total Policy evaluations by outcome.")
        appendLine("# TYPE sentinel_evaluations_total counter")
        Decision.entries.forEach { decision ->
            appendLine("sentinel_evaluations_total{decision=\"${decision.name.lowercase()}\"} ${evaluations[decision]?.sum() ?: 0}")
        }
        appendLine("# HELP sentinel_idempotency_replays_total Requests served from an idempotent result.")
        appendLine("# TYPE sentinel_idempotency_replays_total counter")
        appendLine("sentinel_idempotency_replays_total ${replays.sum()}")
    }
}

package com.apex.guardrails

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.header
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * A small, reusable token-bucket guardrail for Ktor services.
 *
 * The storage is intentionally process-local. For a multi-replica deployment, replace
 * [BucketStore] with a Redis-backed implementation while keeping the plugin API.
 */
class TenantRateLimitConfig {
    var capacity: Int = 120
    var refillPerSecond: Double = 2.0
    var key: (ApplicationCall) -> String = { call ->
        call.request.headers["X-Tenant-Id"] ?: "anonymous"
    }
    var exclude: (ApplicationCall) -> Boolean = { false }
    var store: BucketStore? = null
}

data class RateLimitDecision(
    val allowed: Boolean,
    val remaining: Int,
    val retryAfterSeconds: Long,
)

interface BucketStore {
    fun consume(key: String, capacity: Int, refillPerSecond: Double): RateLimitDecision
}

class RateLimitExceeded(val retryAfterSeconds: Long) : RuntimeException("rate limit exceeded")

class InMemoryBucketStore(
    private val clock: Clock = Clock.systemUTC(),
    private val maxBuckets: Int = 100_000,
) : BucketStore {
    private data class Bucket(var tokens: Double, var lastRefillMillis: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val calls = AtomicLong()

    override fun consume(key: String, capacity: Int, refillPerSecond: Double): RateLimitDecision {
        require(capacity > 0) { "capacity must be positive" }
        require(refillPerSecond > 0.0) { "refillPerSecond must be positive" }
        val now = clock.millis()
        val boundedKey = if (buckets.containsKey(key) || buckets.size < maxBuckets) key else "__overflow__"
        val bucket = buckets.computeIfAbsent(boundedKey) { Bucket(capacity.toDouble(), now) }

        val decision = synchronized(bucket) {
            val elapsedSeconds = (now - bucket.lastRefillMillis).coerceAtLeast(0) / 1_000.0
            bucket.tokens = min(capacity.toDouble(), bucket.tokens + elapsedSeconds * refillPerSecond)
            bucket.lastRefillMillis = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                RateLimitDecision(true, bucket.tokens.toInt(), 0)
            } else {
                val retryAfter = kotlin.math.ceil((1.0 - bucket.tokens) / refillPerSecond).toLong()
                RateLimitDecision(false, 0, retryAfter.coerceAtLeast(1))
            }
        }

        // Bounded housekeeping without a dedicated background job.
        if (calls.incrementAndGet() % 1_000L == 0L) {
            val staleBefore = now - 30 * 60 * 1_000L
            buckets.entries.removeIf { it.value.lastRefillMillis < staleBefore }
        }
        return decision
    }
}

val TenantRateLimit = createApplicationPlugin(
    name = "TenantRateLimit",
    createConfiguration = ::TenantRateLimitConfig,
) {
    val capacity = pluginConfig.capacity
    val refillPerSecond = pluginConfig.refillPerSecond
    val keyProvider = pluginConfig.key
    val exclude = pluginConfig.exclude
    val store = pluginConfig.store ?: InMemoryBucketStore()

    onCall { call ->
        if (exclude(call)) return@onCall
        val decision = store.consume(keyProvider(call), capacity, refillPerSecond)
        call.response.header("X-RateLimit-Limit", capacity.toString())
        call.response.header("X-RateLimit-Remaining", decision.remaining.toString())
        if (!decision.allowed) {
            call.response.header("Retry-After", decision.retryAfterSeconds.toString())
            throw RateLimitExceeded(decision.retryAfterSeconds)
        }
    }
}

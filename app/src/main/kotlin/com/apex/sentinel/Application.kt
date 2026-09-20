package com.apex.sentinel

import com.apex.guardrails.TenantRateLimit
import com.apex.sentinel.api.configureRouting
import com.apex.sentinel.application.DeterministicIncidentAnalyst
import com.apex.sentinel.application.Role
import com.apex.sentinel.application.SentinelService
import com.apex.sentinel.application.SentinelStore
import com.apex.sentinel.application.TenantPrincipal
import com.apex.sentinel.config.AppConfig
import com.apex.sentinel.domain.PolicyEngine
import com.apex.sentinel.infrastructure.InMemorySentinelStore
import com.apex.sentinel.infrastructure.Metrics
import com.apex.sentinel.infrastructure.PostgresSentinelStore
import com.apex.sentinel.infrastructure.SharedFlowEvaluationEvents
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.sse.SSE
import io.ktor.server.request.path
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module(testing: Boolean = false, dependencies: AppDependencies? = null) {
    val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }
    val config = dependencies?.config ?: AppConfig.from(environment.config)
    val store = dependencies?.store ?: createStore(config, json)
    val events = dependencies?.events ?: SharedFlowEvaluationEvents()
    val metrics = dependencies?.metrics ?: Metrics()
    val service = SentinelService(store, PolicyEngine(), events, DeterministicIncidentAnalyst())

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
    }
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        verify { it.length in 8..128 && it.all { character -> character.isLetterOrDigit() || character in "-_." } }
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        disableDefaultColors()
    }
    install(ContentNegotiation) { json(json) }
    install(RequestBodyLimit) { bodyLimit { 64 * 1024L } }
    install(Compression)
    install(SSE)
    install(CORS) {
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("Idempotency-Key")
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        if (!config.production) anyHost()
    }
    install(RequestValidation) {
        validate<com.apex.sentinel.domain.EvaluationRequest> { request ->
            val errors = buildList {
                if (request.eventId.isBlank() || request.eventId.length > 200) add("eventId must contain 1 to 200 characters")
                if (request.eventType.isBlank() || request.eventType.length > 100) add("eventType must contain 1 to 100 characters")
                if (request.subjectId.isBlank() || request.subjectId.length > 200) add("subjectId must contain 1 to 200 characters")
                if (request.signals.trustTier !in 0..3) add("trustTier must be between 0 and 3")
                if (request.signals.requestsPerMinute !in 0..1_000_000) add("requestsPerMinute is outside the accepted range")
                if (request.signals.ipRisk !in 0.0..1.0) add("ipRisk must be between 0 and 1")
                if (request.signals.deviceIntegrity.isBlank() || request.signals.deviceIntegrity.length > 100) {
                    add("deviceIntegrity must contain 1 to 100 characters")
                }
                if (request.occurredAt != null && runCatching { Instant.parse(request.occurredAt) }.isFailure) {
                    add("occurredAt must be an ISO-8601 instant")
                }
                if (request.signals.attributes.size > 32) add("signals.attributes may contain at most 32 entries")
            }
            if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
        }
    }
    install(Authentication) {
        bearer("tenant") {
            realm = "Apex Sentinel"
            authenticate { credential ->
                config.apiKeys.entries.firstOrNull { constantTimeEquals(it.value, credential.token) }
                    ?.let { TenantPrincipal(it.key, Role.TENANT) }
            }
        }
        bearer("admin") {
            realm = "Apex Sentinel administration"
            authenticate { credential ->
                if (constantTimeEquals(config.adminKey, credential.token)) TenantPrincipal("admin", Role.ADMIN) else null
            }
        }
    }
    install(TenantRateLimit) {
        capacity = config.rateLimitCapacity
        refillPerSecond = config.rateLimitRefillPerSecond
        key = { call -> sha256(call.request.headers[HttpHeaders.Authorization] ?: "anonymous") }
        exclude = { call ->
            val path = call.request.path()
            path.startsWith("/health/") || path == "/metrics" || path.startsWith("/docs/")
        }
    }

    configureRouting(service, store, events, metrics, config.storage)
    if (!testing) monitor.subscribe(ApplicationStopped) { store.close() }
}

data class AppDependencies(
    val config: AppConfig,
    val store: SentinelStore,
    val events: SharedFlowEvaluationEvents = SharedFlowEvaluationEvents(),
    val metrics: Metrics = Metrics(),
)

private fun createStore(config: AppConfig, json: Json): SentinelStore = when (config.storage) {
    "memory" -> InMemorySentinelStore()
    "postgres" -> PostgresSentinelStore(config.databaseUrl, config.databaseUser, config.databasePassword, json)
    else -> error("Unsupported storage ${config.storage}")
}

private fun constantTimeEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
    expected.toByteArray(Charsets.UTF_8),
    actual.toByteArray(Charsets.UTF_8),
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

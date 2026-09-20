package com.apex.sentinel.api

import com.apex.guardrails.RateLimitExceeded
import com.apex.sentinel.application.EvaluationEvents
import com.apex.sentinel.application.EvaluationNotFound
import com.apex.sentinel.application.IdempotencyConflict
import com.apex.sentinel.application.InvalidRequest
import com.apex.sentinel.application.SentinelService
import com.apex.sentinel.application.SentinelStore
import com.apex.sentinel.application.TenantPrincipal
import com.apex.sentinel.application.VersionConflict
import com.apex.sentinel.domain.PolicyDocument
import com.apex.sentinel.infrastructure.Metrics
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.sse.ServerSentEvent
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json

fun Application.configureRouting(
    service: SentinelService,
    store: SentinelStore,
    events: EvaluationEvents,
    metrics: Metrics,
    storageName: String,
) {
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, "validation_failed", cause.reasons.joinToString("; "))
        }
        exception<BadRequestException> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, "bad_request", cause.message ?: "Malformed request")
        }
        exception<InvalidRequest> { call, cause ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", cause.message ?: "Invalid request")
        }
        exception<EvaluationNotFound> { call, cause ->
            call.respondError(HttpStatusCode.NotFound, "not_found", cause.message ?: "Not found")
        }
        exception<VersionConflict> { call, cause ->
            call.respondError(HttpStatusCode.Conflict, "version_conflict", cause.message ?: "Version conflict")
        }
        exception<IdempotencyConflict> { call, cause ->
            call.respondError(HttpStatusCode.Conflict, "idempotency_conflict", cause.message ?: "Idempotency conflict")
        }
        exception<RateLimitExceeded> { call, cause ->
            call.respondError(
                HttpStatusCode.TooManyRequests,
                "rate_limit_exceeded",
                "Retry after ${cause.retryAfterSeconds} second(s)",
            )
        }
        exception<Throwable> { call, cause ->
            this@configureRouting.environment.log.error("Unhandled request failure", cause)
            call.respondError(HttpStatusCode.InternalServerError, "internal_error", "An unexpected error occurred")
        }
    }

    routing {
        get("/health/live") { call.respond(HealthResponse("UP")) }
        get("/health/ready") {
            if (store.isReady()) call.respond(HealthResponse("UP", storageName))
            else call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("DOWN", storageName))
        }
        get("/metrics") { call.respondText(metrics.prometheus(), ContentType.Text.Plain) }
        staticResources("/docs", "docs")

        authenticate("tenant") {
            route("/v1") {
                post("/evaluations") {
                    val tenant = call.principal<TenantPrincipal>()!!.tenantId
                    val idempotencyKey = call.request.headers["Idempotency-Key"]
                        ?: throw InvalidRequest("Idempotency-Key header is required")
                    val result = service.evaluate(
                        tenant,
                        idempotencyKey,
                        call.receive<com.apex.sentinel.domain.EvaluationRequest>(),
                    )
                    metrics.evaluation(result.decision, result.replayed)
                    call.respond(if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Created, result)
                }
                get("/evaluations/{id}") {
                    val tenant = call.principal<TenantPrincipal>()!!.tenantId
                    call.respond(service.get(tenant, call.parameters["id"] ?: throw InvalidRequest("id is required")))
                }
                get("/incidents/brief") {
                    val tenant = call.principal<TenantPrincipal>()!!.tenantId
                    val rawMinutes = call.request.queryParameters["lookbackMinutes"]
                    val minutes = rawMinutes?.toIntOrNull()
                        ?: if (rawMinutes == null) 60 else throw InvalidRequest("lookbackMinutes must be an integer")
                    call.respond(service.incidentBrief(tenant, minutes))
                }
                sse("/evaluations/stream") {
                    val tenant = call.principal<TenantPrincipal>()!!.tenantId
                    events.stream(tenant).collect { result ->
                        send(
                            ServerSentEvent(
                                data = Json.encodeToString(com.apex.sentinel.domain.EvaluationResult.serializer(), result),
                                event = "evaluation",
                                id = result.id,
                            ),
                        )
                    }
                }
            }
        }

        authenticate("admin") {
            route("/v1/admin/tenants/{tenantId}/policies") {
                post("/validate") {
                    call.respond(service.validatePolicy(call.receive<PolicyDocument>()))
                }
                post {
                    val tenantId = call.parameters["tenantId"] ?: throw InvalidRequest("tenantId is required")
                    call.respond(HttpStatusCode.Created, service.activatePolicy(tenantId, call.receive()))
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respond(status, ErrorResponse(code, message, callId))

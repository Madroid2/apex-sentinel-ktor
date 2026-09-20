package com.apex.sentinel

import com.apex.sentinel.config.AppConfig
import com.apex.sentinel.infrastructure.InMemorySentinelStore
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ApplicationTest {
    private val config = AppConfig(
        environment = "test",
        storage = "memory",
        databaseUrl = "unused",
        databaseUser = "unused",
        databasePassword = "unused",
        apiKeys = mapOf("apex" to "test-tenant-key"),
        adminKey = "test-admin-key",
        rateLimitCapacity = 1_000,
        rateLimitRefillPerSecond = 1_000.0,
    )

    @Test
    fun `health is public but evaluations require authentication`() = testApplication {
        application { module(testing = true, dependencies = AppDependencies(config, InMemorySentinelStore())) }

        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/evaluations").status)
    }

    @Test
    fun `evaluation is idempotent and does not recompute its decision`() = testApplication {
        application { module(testing = true, dependencies = AppDependencies(config, InMemorySentinelStore())) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val first = client.post("/v1/evaluations") {
            bearerAuth("test-tenant-key")
            header("Idempotency-Key", "attempt-42")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(validRequest)
        }
        val second = client.post("/v1/evaluations") {
            bearerAuth("test-tenant-key")
            header("Idempotency-Key", "attempt-42")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(validRequest)
        }

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        val firstJson = first.body<JsonObject>()
        val secondJson = second.body<JsonObject>()
        assertEquals(firstJson["id"]?.jsonPrimitive?.content, secondJson["id"]?.jsonPrimitive?.content)
        assertEquals("BLOCK", secondJson["decision"]?.jsonPrimitive?.content)
        assertEquals("true", secondJson["replayed"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reusing an idempotency key for a different payload is a conflict`() = testApplication {
        application { module(testing = true, dependencies = AppDependencies(config, InMemorySentinelStore())) }
        client.post("/v1/evaluations") {
            bearerAuth("test-tenant-key")
            header("Idempotency-Key", "reused-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(validRequest)
        }

        val conflict = client.post("/v1/evaluations") {
            bearerAuth("test-tenant-key")
            header("Idempotency-Key", "reused-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(validRequest.replace("150", "1"))
        }

        assertEquals(HttpStatusCode.Conflict, conflict.status)
    }

    @Test
    fun `tenant data is isolated`() = testApplication {
        val isolatedConfig = config.copy(apiKeys = mapOf("apex" to "test-tenant-key", "other" to "other-key"))
        application { module(testing = true, dependencies = AppDependencies(isolatedConfig, InMemorySentinelStore())) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val created = client.post("/v1/evaluations") {
            bearerAuth("test-tenant-key")
            header("Idempotency-Key", "isolated")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(validRequest)
        }.body<JsonObject>()

        val response = client.get("/v1/evaluations/${created["id"]?.jsonPrimitive?.content}") {
            bearerAuth("other-key")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `admin can activate a newer tenant policy`() = testApplication {
        application { module(testing = true, dependencies = AppDependencies(config, InMemorySentinelStore())) }
        val response = client.post("/v1/admin/tenants/apex/policies") {
            bearerAuth("test-admin-key")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(
                """{"version":2,"reviewAt":30,"blockAt":70,"rules":[{"id":"country","description":"review a country","all":[{"field":"country","operator":"EQUALS","value":"ZZ"}],"score":35,"reason":"country_review"}]}""",
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertNotEquals("", response.headers[HttpHeaders.XRequestId])
    }

    private val validRequest =
        """{"eventId":"evt-42","eventType":"auction","subjectId":"sha256:abc","signals":{"trustTier":0,"requestsPerMinute":150,"ipRisk":0.1,"deviceIntegrity":"UNKNOWN","emulator":false}}"""
}

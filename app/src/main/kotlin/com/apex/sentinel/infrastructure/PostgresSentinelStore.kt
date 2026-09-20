package com.apex.sentinel.infrastructure

import com.apex.sentinel.application.SentinelStore
import com.apex.sentinel.application.StoredEvaluation
import com.apex.sentinel.application.IdempotencyConflict
import com.apex.sentinel.application.VersionConflict
import com.apex.sentinel.domain.EvaluationRequest
import com.apex.sentinel.domain.EvaluationResult
import com.apex.sentinel.domain.PolicyDocument
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class PostgresSentinelStore(
    jdbcUrl: String,
    username: String,
    password: String,
    private val json: Json,
) : SentinelStore {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = username
        this.password = password
        maximumPoolSize = 10
        minimumIdle = 2
        connectionTimeout = 3_000
        validationTimeout = 1_000
        leakDetectionThreshold = 20_000
        poolName = "sentinel-postgres"
    })

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    override suspend fun activePolicy(tenantId: String): PolicyDocument? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT policy_json FROM policies WHERE tenant_id = ? AND active = TRUE",
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) json.decodeFromString<PolicyDocument>(rows.getString(1)) else null
                }
            }
        }
    }

    override suspend fun activatePolicy(tenantId: String, policy: PolicyDocument): PolicyDocument =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { statement ->
                        statement.setString(1, tenantId)
                        statement.execute()
                    }
                    val current = connection.prepareStatement(
                        "SELECT COALESCE(MAX(version), 0) FROM policies WHERE tenant_id = ?",
                    ).use { statement ->
                        statement.setString(1, tenantId)
                        statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                    }
                    if (policy.version <= current) {
                        throw VersionConflict("policy version must be greater than existing version $current")
                    }
                    connection.prepareStatement("UPDATE policies SET active = FALSE WHERE tenant_id = ?").use {
                        it.setString(1, tenantId)
                        it.executeUpdate()
                    }
                    connection.prepareStatement(
                        "INSERT INTO policies (tenant_id, version, policy_json, active, created_at) VALUES (?, ?, ?, TRUE, ?)",
                    ).use {
                        it.setString(1, tenantId)
                        it.setInt(2, policy.version)
                        it.setString(3, json.encodeToString(policy))
                        it.setTimestamp(4, Timestamp.from(Instant.now()))
                        it.executeUpdate()
                    }
                    connection.commit()
                    policy
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                }
            }
        }

    override suspend fun saveOrGet(evaluation: StoredEvaluation): Pair<EvaluationResult, Boolean> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO evaluations
                        (tenant_id, id, idempotency_key, event_id, request_json, decision_json, decision, score, evaluated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, evaluation.tenantId)
                    statement.setObject(2, UUID.fromString(evaluation.result.id))
                    statement.setString(3, evaluation.idempotencyKey)
                    statement.setString(4, evaluation.request.eventId)
                    statement.setString(5, json.encodeToString(EvaluationRequest.serializer(), evaluation.request))
                    statement.setString(6, json.encodeToString(EvaluationResult.serializer(), evaluation.result))
                    statement.setString(7, evaluation.result.decision.name)
                    statement.setInt(8, evaluation.result.score)
                    statement.setTimestamp(9, Timestamp.from(Instant.parse(evaluation.result.evaluatedAt)))
                    if (statement.executeUpdate() == 1) return@withContext evaluation.result to false
                }
                val existing = findByIdempotencyKey(connection, evaluation.tenantId, evaluation.idempotencyKey)
                    ?: error("idempotency conflict existed but could not be read")
                if (existing.first != evaluation.request) {
                    throw IdempotencyConflict("Idempotency-Key was already used with a different request")
                }
                existing.second to true
            }
        }

    override suspend fun findEvaluation(tenantId: String, id: String): EvaluationResult? = withContext(Dispatchers.IO) {
        runCatching { UUID.fromString(id) }.getOrNull() ?: return@withContext null
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT decision_json FROM evaluations WHERE tenant_id = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, tenantId)
                statement.setObject(2, UUID.fromString(id))
                statement.executeQuery().use(::readOne)
            }
        }
    }

    override suspend fun flaggedSince(tenantId: String, since: Instant, limit: Int): List<EvaluationResult> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT decision_json FROM evaluations
                    WHERE tenant_id = ? AND decision != 'ACCEPT' AND evaluated_at >= ?
                    ORDER BY evaluated_at DESC LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tenantId)
                    statement.setTimestamp(2, Timestamp.from(since))
                    statement.setInt(3, limit)
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(json.decodeFromString<EvaluationResult>(rows.getString(1))) }
                    }
                }
            }
        }

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            dataSource.connection.use { it.prepareStatement("SELECT 1").use { query -> query.execute() } }
        }.isSuccess
    }

    override fun close() = dataSource.close()

    private fun findByIdempotencyKey(
        connection: java.sql.Connection,
        tenantId: String,
        key: String,
    ): Pair<EvaluationRequest, EvaluationResult>? = connection.prepareStatement(
        "SELECT request_json, decision_json FROM evaluations WHERE tenant_id = ? AND idempotency_key = ?",
    ).use { statement ->
        statement.setString(1, tenantId)
        statement.setString(2, key)
        statement.executeQuery().use { rows ->
            if (!rows.next()) null else {
                json.decodeFromString<EvaluationRequest>(rows.getString(1)) to
                    json.decodeFromString<EvaluationResult>(rows.getString(2))
            }
        }
    }

    private fun readOne(rows: ResultSet): EvaluationResult? =
        if (rows.next()) json.decodeFromString<EvaluationResult>(rows.getString(1)) else null
}

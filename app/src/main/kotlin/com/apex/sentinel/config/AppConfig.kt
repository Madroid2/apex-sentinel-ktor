package com.apex.sentinel.config

import io.ktor.server.config.ApplicationConfig

data class AppConfig(
    val environment: String,
    val storage: String,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val apiKeys: Map<String, String>,
    val adminKey: String,
    val rateLimitCapacity: Int,
    val rateLimitRefillPerSecond: Double,
) {
    val production: Boolean get() = environment.equals("production", ignoreCase = true)

    fun validate() {
        require(storage in setOf("memory", "postgres")) { "SENTINEL_STORAGE must be memory or postgres" }
        require(apiKeys.isNotEmpty()) { "At least one tenant API key is required" }
        require(apiKeys.values.all { it.length >= 12 || !production }) {
            "Production tenant API keys must contain at least 12 characters"
        }
        require(adminKey.length >= 16 || !production) { "Production admin key must contain at least 16 characters" }
        if (production) {
            require(storage == "postgres") { "Production must use PostgreSQL storage" }
            require(databasePassword.isNotBlank() && databasePassword != "sentinel") {
                "Production database password must be provided through DATABASE_PASSWORD"
            }
            require(apiKeys.values.none { it.startsWith("dev-") }) { "Development tenant keys are forbidden in production" }
            require(!adminKey.startsWith("dev-")) { "Development admin key is forbidden in production" }
        }
    }

    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            val root = config.config("sentinel")
            return AppConfig(
                environment = root.property("environment").getString(),
                storage = root.property("storage").getString(),
                databaseUrl = root.property("databaseUrl").getString(),
                databaseUser = root.property("databaseUser").getString(),
                databasePassword = root.property("databasePassword").getString(),
                apiKeys = parseApiKeys(root.property("apiKeys").getString()),
                adminKey = root.property("adminKey").getString(),
                rateLimitCapacity = root.property("rateLimit.capacity").getString().toInt(),
                rateLimitRefillPerSecond = root.property("rateLimit.refillPerSecond").getString().toDouble(),
            ).also(AppConfig::validate)
        }

        internal fun parseApiKeys(raw: String): Map<String, String> = raw.split(',')
            .filter(String::isNotBlank)
            .associate { entry ->
                val parts = entry.split(':', limit = 2)
                require(parts.size == 2 && parts.all(String::isNotBlank)) {
                    "SENTINEL_API_KEYS must use tenant:key pairs separated by commas"
                }
                parts[0].trim() to parts[1].trim()
            }
    }
}

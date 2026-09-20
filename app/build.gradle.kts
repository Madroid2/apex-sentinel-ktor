plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.apex.sentinel.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("apex-sentinel.jar")
    }
}

dependencies {
    implementation(project(":ktor-guardrails"))

    implementation("io.ktor:ktor-server-core:3.6.0")
    implementation("io.ktor:ktor-server-netty:3.6.0")
    implementation("io.ktor:ktor-server-auth:3.6.0")
    implementation("io.ktor:ktor-server-body-limit:3.6.0")
    implementation("io.ktor:ktor-server-call-id:3.6.0")
    implementation("io.ktor:ktor-server-call-logging:3.6.0")
    implementation("io.ktor:ktor-server-compression:3.6.0")
    implementation("io.ktor:ktor-server-config-yaml:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-server-cors:3.6.0")
    implementation("io.ktor:ktor-server-default-headers:3.6.0")
    implementation("io.ktor:ktor-server-request-validation:3.6.0")
    implementation("io.ktor:ktor-server-status-pages:3.6.0")
    implementation("io.ktor:ktor-server-sse:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("org.flywaydb:flyway-core:13.7.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.7.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.6.0")
}

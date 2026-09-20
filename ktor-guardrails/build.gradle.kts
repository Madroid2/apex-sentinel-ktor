plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api("io.ktor:ktor-server-core:3.6.0")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
}

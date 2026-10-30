plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
}

kotlin { jvmToolchain(17) }
application { mainClass.set("com.audipp.server.MainKt") }
dependencyLocking { lockAllConfigurations() }

val ktorVersion = "3.4.3"
dependencies {
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("com.auth0:java-jwt:4.5.1")
    implementation("com.auth0:jwks-rsa:0.24.1")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.postgresql:postgresql:42.7.14")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation("junit:junit:4.13.2")
}

tasks.test { maxParallelForks = 1 }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

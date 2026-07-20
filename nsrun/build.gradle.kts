plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":protocol-local"))
    implementation(project(":local-client"))
    implementation(libs.kotlinx.serialization.json)

    // PTY4J ships native helpers for Linux and macOS. NSRun falls back to ProcessBuilder when unavailable.
    implementation(libs.pty4j)
    implementation(libs.jna)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

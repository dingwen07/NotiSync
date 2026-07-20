plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":protocol-local"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.junixsocket.core)
    implementation(libs.jna)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

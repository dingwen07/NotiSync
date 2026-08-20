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
    implementation(project(":ssh-agent-core"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.junixsocket.core)
    implementation(libs.jna)
    implementation(libs.bouncycastle.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}

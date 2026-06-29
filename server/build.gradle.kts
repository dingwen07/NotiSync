plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.ktor.server.cio.EngineMain")
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":protocol-crypto"))
    implementation(libs.tink) // provides the Tink runtime for :protocol-crypto (compileOnly there)

    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.config.yaml)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.sqlite.jdbc)
    implementation(libs.hikari)

    implementation(libs.firebase.admin)
    implementation(libs.google.auth.library.oauth2.http) // GoogleCredentials for FCM HTTP v1 auth (Fcm.kt)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.ktor.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

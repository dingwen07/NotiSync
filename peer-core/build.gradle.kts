plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":protocol"))
    api(project(":protocol-crypto"))

    // protocol-crypto deliberately leaves the concrete Tink runtime to its consumers.
    compileOnly(libs.tink)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.tink)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

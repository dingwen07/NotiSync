plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // enables @Serializable in tests (golden-vector schema); no main-source @Serializable types
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":protocol"))

    // Tink is the implementation, but it is provided by each consumer:
    //   * the Android app supplies tink-android
    //   * the Ktor server supplies tink (JVM)
    // Both expose the same com.google.crypto.tink.* API, so this module compiles
    // against it as compileOnly and never bundles a concrete Tink artifact.
    compileOnly(libs.tink)

    testImplementation(libs.tink) // tests run on the JVM -> use the JVM Tink artifact
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.2.x compiles Kotlin via built-in Kotlin (bundled KGP 2.2.10) — do NOT apply
    // org.jetbrains.kotlin.android (it conflicts). The compose + serialization compiler plugins
    // coexist with built-in Kotlin. v1 deliberately avoids KSP/Hilt/Room (annotation processing
    // is a moving target on AGP 9) and uses manual DI + DataStore instead.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

android {
    namespace = "net.extrawdw.apps.notisync"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.extrawdw.apps.notisync"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    // Shared protocol + crypto (consumed verbatim from the JVM modules); tink-android backs the
    // compileOnly Tink in :protocol-crypto on the Android side.
    implementation(project(":protocol"))
    implementation(project(":protocol-crypto"))
    implementation(libs.tink.android)

    // Compose / Material 3 Expressive
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    // NOTE: coil3 3.5.0 is omitted in v1 — it drags in kotlin-stdlib 2.4.0 (unreadable by AGP
    // 9.2.1's bundled Kotlin 2.2.10 compiler). v1 is text-first and loads app icons directly from
    // PackageManager; revisit async image loading once the toolchain catches up to Kotlin 2.4.

    // Core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // Persistence (no codegen): Preferences + structured values serialized via kotlinx-serialization.
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    // Transport (Ktor client) — dev WebSocket + control plane
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.cbor)

    // FCM
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // QR pairing — Google code scanner (no CAMERA permission) + ZXing for QR generation
    implementation(libs.play.services.code.scanner)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

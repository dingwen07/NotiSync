import java.util.Properties

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

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "net.extrawdw.apps.notisync"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.extrawdw.apps.notisync"
        minSdk = 34
        targetSdk = 37
        versionCode = 13
        versionName = "1.1.1-rc.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val cloudProjectNumber = localProperties.getProperty("PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER")
            ?: localProperties.getProperty("CLOUD_PROJECT_NUMBER")
            ?: "0"
        buildConfigField("long", "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER", "${cloudProjectNumber.trim()}L")
        // NS2 epoch rotation: the foundation (operational key at epoch 1, key-epoch publish, signed floor +
        // generation ring) is always on; this flag gates ONLY whether the client ever mints a SECOND epoch
        // (the scheduled rotation + pre-warm state machine in RotationManager). OFF ⇒ epoch 1 forever, so the
        // complex rotation path never runs until it's been exercised on TEST. Override per build via
        // local.properties (ENABLE_ROTATION=true) if desired.
        val enableRotation = localProperties.getProperty("ENABLE_ROTATION")?.trim()?.lowercase() == "true"
        buildConfigField("boolean", "ENABLE_ROTATION", enableRotation.toString())
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEBUG_KEY", (localProperties.getProperty("DEBUG_KEY") ?: "").asBuildConfigString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "DEBUG_KEY", "".asBuildConfigString())
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep",
            )
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

    // Play: Your app uses an outdated SDK version of androidx.fragment:fragment
    // Raise the transitive androidx.fragment that play-services-base/basement pin at 1.1.0
    // A constraint not a direct dependency since the app uses no Fragments.
    constraints {
        implementation(libs.androidx.fragment) {
            because("play-services pins androidx.fragment:1.1.0, flagged outdated by Play's SDK index")
        }
    }

    // Persistence (no codegen): Preferences + structured values serialized via kotlinx-serialization.
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    // Directly used for the small App Store (iTunes Lookup) icon JSON; also exported transitively by :protocol.
    implementation(libs.kotlinx.serialization.json)

    // Transport (Ktor client) — dev WebSocket + control plane + App Store icon fetch
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
    implementation(libs.play.integrity)
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

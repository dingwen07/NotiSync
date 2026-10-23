import com.google.gms.googleservices.GoogleServicesTask
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
}

// Reuse the main app's Firebase client configuration until this module has its own.
// This only selects the JSON client; the installed application ID remains the .debug package.
if (!file("google-services.json").isFile) {
    androidComponents.onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
        tasks.named<GoogleServicesTask>("process${variantName}GoogleServices") {
            googleServicesJsonFiles.set(listOf(rootProject.file("app/google-services.json")))
            applicationId.set("net.extrawdw.apps.notisync")
        }
    }
}

room3 {
    // Only :app maintains the checked-in schema history; avoid concurrent writes from both modules.
    schemaDirectory(layout.buildDirectory.dir("schemas"))
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

android {
    namespace = "net.extrawdw.apps.notisync"
    compileSdk {
        version = release(37)  {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "net.extrawdw.apps.notisync.debug"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "debug"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Key Epoch rotation
        val enableRotation = localProperties.getProperty("ENABLE_ROTATION")?.trim()?.lowercase() == "true"
        buildConfigField("boolean", "ENABLE_ROTATION", enableRotation.toString())
    }

    sourceSets.configureEach {
        setRoot(rootProject.file("app/src/$name").path)
    }
    sourceSets.named("androidTest") {
        // Include the shared migration history alongside this module's generated schemas.
        assets.directories.add(rootProject.file("app/schemas").path)
    }

    buildTypes {
        debug { }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
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
        aidl = true
    }
    packaging {
        resources.pickFirsts += "META-INF/LICENSE.md"
        resources.pickFirsts += "META-INF/DEPENDENCIES"
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
    implementation(project(":peer-core"))
    implementation(project(":ssh-agent-core"))
    implementation(project(":scrcpy-server"))
    implementation(project(":screen-session"))
    implementation(libs.tink.android)
    implementation(libs.apache.sshd.common)
    implementation(libs.apache.sshd.putty)
    implementation(libs.bouncycastle.provider)
    implementation(libs.bouncycastle.pkix)

    // Compose / Material 3 Expressive
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.animation.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)
    implementation(libs.reorderable)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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

    // Persistence. Room owns database creation and schema upgrades.
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room3.compiler)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    // Privileged screen capture/input runs as a short-lived, non-daemon Shizuku UserService. The provider
    // artifact only discovers the separately installed Shizuku Manager; it does not bundle or start it.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.openpgp.api)
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
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.perf)

    // App Check — genuine-app/device attestation (Play Integrity provider, handled internally by Firebase).
    // The broker verifies the App Check JWT locally against the App Check JWKS. Debug builds use the debug
    // provider (a console-registered debug token), kept out of release via debugImplementation + src/debug.
    implementation(libs.firebase.appcheck)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    // QR pairing — Google code scanner (no CAMERA permission) + ZXing for QR generation
    implementation(libs.play.services.code.scanner)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room3.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// :protocol is the shared wire model + CBOR/JSON codec. It is Kotlin Multiplatform so the SAME types
// and the SAME kotlinx-serialization CBOR encoder compile to both the JVM (consumed unchanged by
// :app/:server/:protocol-crypto via the jvm() variant) and Apple targets (shipped to the native iOS
// client as an XCFramework). Sharing the codec is what makes signed payloads byte-identical across
// platforms by construction. Sources stay in src/main + src/test (mapped via srcDir) so the conversion
// is invisible to the existing JVM modules and to git history.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)

    jvm()

    // iOS device + Apple-silicon simulator, bundled into one XCFramework (iosX64/Intel-sim omitted —
    // add only if an Intel CI runner ever needs it). assemble with :protocol:assembleNotiSyncProtocolXCFramework.
    val xcframework = XCFramework("NotiSyncProtocol")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "NotiSyncProtocol"
            isStatic = true
            xcframework.add(this)
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }

    sourceSets {
        getByName("commonMain") {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(libs.kotlinx.serialization.cbor)
                api(libs.kotlinx.serialization.json)
                api(libs.kotlinx.coroutines.core)
            }
        }
        // Existing tests are JUnit (JVM-only) — keep them on the jvmTest source set rather than commonTest
        // so they run unchanged; pure DTO/codec tests can migrate to commonTest later if Native coverage is wanted.
        getByName("jvmTest") {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

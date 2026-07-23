plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.genymobile.scrcpy"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 34
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        disable += "UseRequiresApi"
    }
}

dependencies {
    testImplementation(libs.junit)
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.bouncycastle.provider)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

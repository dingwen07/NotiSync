plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":protocol"))
    api(libs.bouncycastle.tls)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

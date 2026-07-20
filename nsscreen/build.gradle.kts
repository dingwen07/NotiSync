plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":protocol-local"))
    implementation(project(":local-client"))
    implementation(project(":screen-session"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

val screenHelperOutput = layout.buildDirectory.file("native/notisync-screen-helper")
val screenHelperSourceDirectory = layout.projectDirectory.dir("src/native/helper")
val screenRuntimeScriptDirectory = layout.projectDirectory.dir("src/native/runtime")
val screenReleaseRuntimeOutput = layout.buildDirectory.dir("release-runtime/current")

val compileScreenHelper = tasks.register<Exec>("compileScreenHelper") {
    group = "build"
    description = "Builds the attach-only SDL/FFmpeg screen viewer for this host."
    inputs.files(fileTree(screenHelperSourceDirectory) {
        include("*.c", "*.h", "*.sh")
    })
    outputs.file(screenHelperOutput)
    commandLine(
        "sh",
        screenHelperSourceDirectory.file("build-helper.sh").asFile.absolutePath,
        screenHelperOutput.get().asFile.absolutePath,
    )
}

val testScreenHelper = tasks.register<Exec>("testScreenHelper") {
    group = "verification"
    description = "Runs framing, UTF-8, geometry, and control-packet checks in the native viewer."
    dependsOn(compileScreenHelper)
    commandLine(screenHelperOutput.get().asFile.absolutePath, "--self-test")
}

tasks.register<Exec>("checkScreenHelperRuntime") {
    group = "verification"
    description = "Checks whether the developer-linked screen helper uses an LGPL-compatible FFmpeg runtime."
    dependsOn(compileScreenHelper)
    commandLine(screenHelperOutput.get().asFile.absolutePath, "--check-runtime")
}

val cleanScreenReleaseRuntime = tasks.register<Delete>("cleanScreenReleaseRuntime") {
    group = "release"
    description = "Removes only the materialized host screen runtime from this project's build directory."
    delete(screenReleaseRuntimeOutput)
}

val prepareScreenReleaseRuntime = tasks.register<Exec>("prepareScreenReleaseRuntime") {
    group = "release"
    description = "Builds or validates the pinned, LGPL-compatible SDL/FFmpeg runtime from explicit caches."
    dependsOn(cleanScreenReleaseRuntime)
    inputs.files(fileTree(screenRuntimeScriptDirectory) {
        include("*.sh", "*.md")
    })
    inputs.property(
        "runtimeCache",
        providers.environmentVariable("NOTISYNC_SCREEN_RUNTIME_CACHE").orElse("<missing>"),
    )
    inputs.property(
        "sourceDirectory",
        providers.environmentVariable("NOTISYNC_SCREEN_SOURCE_DIR").orElse("<cache-only>"),
    )
    outputs.dir(screenReleaseRuntimeOutput)
    commandLine(
        "sh",
        screenRuntimeScriptDirectory.file("build-release-runtime.sh").asFile.absolutePath,
        screenReleaseRuntimeOutput.get().asFile.absolutePath,
    )
}

tasks.register<Exec>("validateScreenReleaseRuntime") {
    group = "release"
    description = "Validates hashes, corresponding source, LGPL flags, ABI names, runpaths, and helper behavior."
    dependsOn(prepareScreenReleaseRuntime)
    commandLine(
        "sh",
        screenRuntimeScriptDirectory.file("validate-release-runtime.sh").asFile.absolutePath,
        screenReleaseRuntimeOutput.get().asFile.absolutePath,
    )
}

tasks.check {
    dependsOn(testScreenHelper)
}

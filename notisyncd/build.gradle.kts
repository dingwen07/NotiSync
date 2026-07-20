import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol-local"))
    implementation(project(":local-client"))
    implementation(project(":protocol"))
    implementation(project(":peer-core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.junixsocket.core)
    implementation(libs.tink)
    implementation(libs.zxing.core)

    implementation(libs.jna)
    runtimeOnly(project(":nsrun"))
    runtimeOnly(project(":nsscreen"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

private val isMacOs = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
private val launcherDirectory = if (isMacOs) "libexec" else "bin"
private val defaultJvmOptions = listOf("--enable-native-access=ALL-UNNAMED")
private val daemonJvmOptions = defaultJvmOptions + listOf(
    // JDK 24+ warns when Protobuf's Unsafe fast path is initialized. Java 21 does not recognize
    // the suppression option, so the daemon also enables forward-compatible VM options.
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--sun-misc-unsafe-memory-access=allow",
)

application {
    mainClass.set("net.extrawdw.notisync.daemon.NotisyncdMainKt")
    applicationName = "notisyncd"
    executableDir = launcherDirectory
    applicationDefaultJvmArgs = daemonJvmOptions
}

private data class DesktopLauncher(
    val name: String,
    val mainClass: String,
)

private val additionalLaunchers = listOf(
    DesktopLauncher("nsrun", "net.extrawdw.notisync.run.NSRunMainKt"),
    DesktopLauncher("nsscreen", "net.extrawdw.notisync.screen.desktop.NSScreenMainKt"),
    DesktopLauncher("notisync", "net.extrawdw.notisync.cli.NotisyncMainKt"),
)

private val screenHelperBinary = project(":nsscreen").layout.buildDirectory.file("native/notisync-screen-helper")
private val screenReleaseRuntime = project(":nsscreen").layout.buildDirectory.dir("release-runtime/current")
private val screenRuntimeScripts = project(":nsscreen").layout.projectDirectory.dir("src/native/runtime")

private val macLauncherSource = layout.projectDirectory.file("src/native/macos/desktop-launcher.c")
private val macLauncherBinary = layout.buildDirectory.file("native/macos/notisync-launcher")
private val java21 = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
private val macLauncherSourceFile = macLauncherSource.asFile
private val macLauncherOutputFile = macLauncherBinary.get().asFile
private val macJavaHome = if (isMacOs) java21.get().metadata.installationPath.asFile else null

private val compileMacLauncher = tasks.register<Exec>("compileMacLauncher") {
    group = "build"
    description = "Builds the universal macOS launcher that hosts the JVM under the CLI executable name."
    inputs.file(macLauncherSource)
    outputs.file(macLauncherBinary)
    enabled = isMacOs
    if (isMacOs) {
        macLauncherOutputFile.parentFile.mkdirs()
        commandLine(
            "xcrun", "clang",
            "-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-Wl,-dead_strip",
            "-mmacosx-version-min=11.0",
            "-arch", "arm64", "-arch", "x86_64",
            "-I${macJavaHome!!.resolve("include")}",
            "-I${macJavaHome.resolve("include/darwin")}",
            macLauncherSourceFile.absolutePath,
            "-o", macLauncherOutputFile.absolutePath,
        )
    } else {
        commandLine("true")
    }
}

private val additionalStartScripts = additionalLaunchers.associateWith { launcher ->
    tasks.register<CreateStartScripts>("${launcher.name}StartScripts") {
        applicationName = launcher.name
        mainClass.set(launcher.mainClass)
        classpath = files(tasks.named("jar"), configurations.runtimeClasspath)
        defaultJvmOpts = defaultJvmOptions
        // Keep these outside build/scripts: the application plugin copies that directory
        // wholesale for the primary launcher.
        outputDir = layout.buildDirectory.dir("additional-start-scripts/${launcher.name}").get().asFile
    }
}

distributions {
    main {
        contents {
            additionalStartScripts.values.forEach { scripts ->
                from(scripts.map { it.outputDir }) {
                    into(launcherDirectory)
                    filePermissions {
                        unix("rwxr-xr-x")
                    }
                }
            }
            from(screenHelperBinary) {
                into("bin")
                filePermissions {
                    unix("rwxr-xr-x")
                }
            }
            from(rootProject.layout.projectDirectory.file("SCREEN_MIRRORING_THIRD_PARTY.md")) {
                into("licenses")
            }
            from(rootProject.layout.projectDirectory.dir("nsscreen/src/native/licenses")) {
                into("licenses")
            }
            from(rootProject.layout.projectDirectory.file("app/src/main/assets/licenses/Bouncy-Castle-MIT.txt")) {
                into("licenses")
            }
            from(rootProject.layout.projectDirectory.file("app/src/main/assets/licenses/scrcpy-Apache-2.0.txt")) {
                into("licenses")
                rename("scrcpy-Apache-2.0.txt", "Apache-2.0.txt")
            }
            from(rootProject.layout.projectDirectory.file("app/src/main/assets/licenses/Shizuku-API-MIT.txt")) {
                into("licenses")
            }
            if (isMacOs) {
                listOf("notisyncd", "nsrun", "nsscreen", "notisync").forEach { launcher ->
                    from(compileMacLauncher) {
                        into("bin")
                        rename("notisync-launcher", launcher)
                        filePermissions {
                            unix("rwxr-xr-x")
                        }
                    }
                }
            }
        }
    }
}

// Release desktop artifacts are deliberately separate from the developer distribution above.
// They never compile against or copy multimedia packages from the host. The nsscreen release task
// consumes a validated immutable cache, building it from explicitly supplied source archives only
// on a cache miss.
private val screenReleaseOs = if (isMacOs) "macos" else "linux"
private val screenReleaseArch = System.getProperty("os.arch").lowercase().let { architecture ->
    when (architecture) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x86_64"
        else -> architecture.replace(Regex("[^a-z0-9._-]"), "-")
    }
}
private val screenReleaseInstallDirectory = layout.buildDirectory.dir("install/notisyncd-screen-release")
private val mainStartScripts = tasks.named<CreateStartScripts>("startScripts")

private val stageScreenReleaseDist = tasks.register<Sync>("stageScreenReleaseDist") {
    group = "release"
    description = "Stages the JVM applications with the validated, relocatable SDL/FFmpeg runtime."
    dependsOn(
        tasks.named("jar"),
        mainStartScripts,
        additionalStartScripts.values,
        ":nsscreen:prepareScreenReleaseRuntime",
    )
    if (isMacOs) {
        dependsOn(compileMacLauncher)
    }
    into(screenReleaseInstallDirectory)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(mainStartScripts.map { it.outputDir }) {
        into(launcherDirectory)
        filePermissions { unix("rwxr-xr-x") }
    }
    additionalStartScripts.values.forEach { scripts ->
        from(scripts.map { it.outputDir }) {
            into(launcherDirectory)
            filePermissions { unix("rwxr-xr-x") }
        }
    }
    from(tasks.named("jar")) {
        into("lib")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
    }
    from(layout.projectDirectory.dir("src/dist"))

    from(screenReleaseRuntime.map { it.dir("bin") }) {
        into("bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(screenReleaseRuntime.map { it.dir("lib") }) {
        into("lib")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(screenReleaseRuntime.map { it.dir("compliance") }) {
        into("licenses/screen-runtime")
    }

    from(rootProject.layout.projectDirectory.file("SCREEN_MIRRORING_THIRD_PARTY.md")) {
        into("licenses")
    }
    from(rootProject.layout.projectDirectory.file("app/src/main/assets/licenses/Bouncy-Castle-MIT.txt")) {
        into("licenses")
    }
    from(rootProject.layout.projectDirectory.file("app/src/main/assets/licenses/scrcpy-Apache-2.0.txt")) {
        into("licenses")
        rename("scrcpy-Apache-2.0.txt", "Apache-2.0.txt")
    }
    from(rootProject.layout.projectDirectory.file("app/src/main/assets/licenses/Shizuku-API-MIT.txt")) {
        into("licenses")
    }

    if (isMacOs) {
        listOf("notisyncd", "nsrun", "nsscreen", "notisync").forEach { launcher ->
            from(compileMacLauncher) {
                into("bin")
                rename("notisync-launcher", launcher)
                filePermissions { unix("rwxr-xr-x") }
            }
        }
    }
}

private val validateInstalledScreenReleaseRuntime = tasks.register<Exec>("validateInstalledScreenReleaseRuntime") {
    group = "release"
    description = "Fails closed unless the installed distribution contains the exact validated runtime and sources."
    dependsOn(stageScreenReleaseDist)
    commandLine(
        "sh",
        screenRuntimeScripts.file("validate-release-runtime.sh").asFile.absolutePath,
        screenReleaseInstallDirectory.get().asFile.absolutePath,
        "--distribution",
    )
}

tasks.register("installScreenReleaseDist") {
    group = "release"
    description = "Installs the host-native, LGPL-compatible screen-enabled desktop release distribution."
    dependsOn(validateInstalledScreenReleaseRuntime)
}

tasks.register<Zip>("screenReleaseDistZip") {
    group = "release"
    description = "Creates a validated host-native screen-enabled release ZIP."
    dependsOn("installScreenReleaseDist")
    archiveFileName.set("notisyncd-screen-$screenReleaseOs-$screenReleaseArch.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(screenReleaseInstallDirectory) {
        into("notisyncd-screen-$screenReleaseOs-$screenReleaseArch")
    }
}

tasks.register<Tar>("screenReleaseDistTar") {
    group = "release"
    description = "Creates a validated host-native screen-enabled release tar.gz."
    dependsOn("installScreenReleaseDist")
    archiveFileName.set("notisyncd-screen-$screenReleaseOs-$screenReleaseArch.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    from(screenReleaseInstallDirectory) {
        into("notisyncd-screen-$screenReleaseOs-$screenReleaseArch")
    }
}

private val installDirectory = layout.buildDirectory.dir("install/notisyncd").get().asFile

listOf("installDist", "distZip", "distTar").forEach { taskName ->
    tasks.named(taskName) {
        dependsOn(":nsscreen:compileScreenHelper")
    }
}

private val launcherSmokeTasks = listOf("notisyncd", "nsrun", "nsscreen", "notisync").map { launcher ->
    val installedExecutable = installDirectory.resolve("bin/$launcher")
    val smokeDataDirectory = layout.buildDirectory.dir("tmp/launcher-smoke/$launcher").get().asFile
    tasks.register<Exec>("smoke${launcher.replaceFirstChar(Char::uppercaseChar)}Launcher") {
        group = "verification"
        description = "Runs the installed $launcher launcher with --help."
        dependsOn(tasks.installDist)
        commandLine(installedExecutable, "--help")
        environment(
            "JAVA_OPTS",
            "-Xms32m -Xmx128m -Dnotisync.dataDir=${smokeDataDirectory.absolutePath}",
        )
    }
}

// Avoid starting three JVMs at once when Gradle is already running Android/KMP test workers.
launcherSmokeTasks.zipWithNext().forEach { (previous, next) ->
    next.configure { mustRunAfter(previous) }
}

tasks.register("smokeInstallDist") {
    group = "verification"
    description = "Smoke-tests all launchers in the installed desktop distribution."
    dependsOn(launcherSmokeTasks)
}

val smokeScreenHelper = tasks.register<Exec>("smokeScreenHelper") {
    group = "verification"
    description = "Runs the installed native screen helper self-test."
    dependsOn(tasks.installDist)
    commandLine(installDirectory.resolve("bin/notisync-screen-helper"), "--self-test")
}

tasks.named("smokeInstallDist") {
    dependsOn(smokeScreenHelper)
}

tasks.test {
    useJUnit()
    if (isMacOs) {
        dependsOn(tasks.installDist)
        systemProperty("notisync.test.installation", installDirectory.absolutePath)
    }
}

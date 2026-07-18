import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":notisync-local-api"))
    implementation(project(":protocol"))
    implementation(project(":peer-core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.junixsocket.core)
    implementation(libs.tink)
    implementation(libs.zxing.core)

    // PTY4J ships native helpers for Linux and macOS. NSRun falls back to ProcessBuilder when unavailable.
    implementation(libs.pty4j)
    implementation(libs.jna)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

private val isMacOs = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }
private val launcherDirectory = if (isMacOs) "libexec" else "bin"
private val defaultJvmOptions = listOf("--enable-native-access=ALL-UNNAMED")

application {
    mainClass.set("net.extrawdw.notisync.daemon.NotisyncdMainKt")
    applicationName = "notisyncd"
    executableDir = launcherDirectory
    applicationDefaultJvmArgs = defaultJvmOptions
}

private data class DesktopLauncher(
    val name: String,
    val mainClass: String,
)

private val additionalLaunchers = listOf(
    DesktopLauncher("nsrun", "net.extrawdw.notisync.run.NSRunMainKt"),
    DesktopLauncher("notisync", "net.extrawdw.notisync.cli.NotisyncMainKt"),
)

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
            if (isMacOs) {
                listOf("notisyncd", "nsrun", "notisync").forEach { launcher ->
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

private val installDirectory = layout.buildDirectory.dir("install/notisyncd").get().asFile

private val launcherSmokeTasks = listOf("notisyncd", "nsrun", "notisync").map { launcher ->
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

tasks.test {
    useJUnit()
    if (isMacOs) {
        dependsOn(tasks.installDist)
        systemProperty("notisync.test.installation", installDirectory.absolutePath)
    }
}

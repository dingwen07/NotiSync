pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.open-keychain.open-keychain") }
        }
    }
}

rootProject.name = "NotiSync"

// Shared protocol + crypto consumed verbatim by both the Android app and the Ktor server.
include(":protocol")
include(":protocol-crypto")
include(":peer-core")
include(":protocol-local")
include(":local-client")
include(":ssh-agent-core")
include(":screen-session")
include(":nsscreen")
include(":scrcpy-server")
include(":nsrun")
include(":notisync-gpg")
include(":notisync-ssh-agent")
include(":notisyncd")
include(":server")

// Android client.
include(":app")

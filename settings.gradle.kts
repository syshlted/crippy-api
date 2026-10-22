// libcrippy — module layout per AGENTS.md L1.
//
// Two artifacts, because crippy D115 publishes two: the contract, which crippy's service and every
// client compile against, and the client library an app integrates. Both are Android libraries,
// because a Binder interface is android.* by definition.

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

// Resolves the JDK 21 toolchain on machines where it is not in a location Gradle auto-detects,
// for crippy's reason: a newer JDK on PATH silently building against an AGP that does not support it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "libcrippy"

include(":crippy-contract")
include(":crippy-client")

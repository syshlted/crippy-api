import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.dsl.LockMode
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// The Kotlin every module compiles with (L9). AGP 9 builds Kotlin in and depends on 2.2.10; one
// classpath holds one version of a plugin, so declaring a newer one here is what AGP's built-in Kotlin
// then uses.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

val ktlintToolVersion = libs.versions.ktlint.get()

// The kotlin-stdlib a published module declares (L17): the oldest the floor's API allows, and one the
// verified set already holds, from AGP's toolchain.
val kotlinFloorStdlib = "2.2.10"
val javaToolchain = 21

// Aggregate unit-test task, for crippy D11's reason: CI and the pre-push hook must not call
// testDebugUnitTest directly. Gradle runs a name-matched task in whichever projects have it without
// complaining that the others lack it, so the first pure-JVM module here — the registry's validator,
// most likely — would be skipped by a bare testDebugUnitTest and the run would still report green.
val allUnitTests =
    tasks.register("allUnitTests") {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs JVM unit tests in every module: `test` for pure-JVM, `testDebugUnitTest` for Android."
    }

allprojects {
    // The coordinates a composite build substitutes on (L1). The file manager declares these and,
    // while both repositories are changing, includes this build instead of resolving them.
    group = "com.systemhalted.crippy"
    version = "0.1.0-SNAPSHOT"

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<KtlintExtension> {
        version.set(ktlintToolVersion)
        // .editorconfig is the style source of truth, shared with the IDE.
    }

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = javaToolchain.toString()
        reports {
            xml.required.set(true)
            html.required.set(true)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // Real JVM default methods and no $DefaultImpls bridge, as in crippy (D11): the bridge is dead
    // code no test can reach, and it counts against the coverage gates.
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
        }
    }

    // The Kotlin an app needs to use what is published here (L17): metadata a Kotlin 2.1 compiler can read,
    // the standard library's 2.2 API and no newer, and kotlin-stdlib 2.2.10 declared rather than the
    // compiler's 2.4.21. 2.2 because it is the oldest language version the compiler does not warn is going;
    // 2.1 compiles and is deprecated. Main sources only: tests may use what they like.
    tasks.withType<KotlinCompile>().matching { !it.name.contains("Test") }.configureEach {
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_2_2)
            apiVersion.set(KotlinVersion.KOTLIN_2_2)
        }
    }
    // AGP's built-in Kotlin and the JVM plugin register the same extension, each when it is applied.
    listOf("org.jetbrains.kotlin.jvm", "com.android.library").forEach { id ->
        plugins.withId(id) { extensions.getByType<KotlinBaseExtension>().coreLibrariesVersion = kotlinFloorStdlib }
    }

    // Wire each module into the aggregate under whichever name its tests have.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        allUnitTests.configure { dependsOn("${this@subprojects.path}:test") }
    }
    plugins.withId("com.android.library") {
        allUnitTests.configure { dependsOn("${this@subprojects.path}:testDebugUnitTest") }
    }

    // What an app receives from this module, locked in gradle.lockfile beside it (L10). It changes
    // only by `--write-locks`, so a new runtime dependency is a diff someone reads, and the lockfile is
    // the list `dependencies.yml` scans. STRICT, so a module whose lockfile is missing fails rather
    // than resolving unlocked.
    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }
    configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
}

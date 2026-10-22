import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// Kotlin support is built into AGP since 9.0; applying org.jetbrains.kotlin.android
// alongside it is an error, not a redundancy.
plugins {
    alias(libs.plugins.android.library)
}

val versions = libs.versions

// The client library (L1): what an app integrates in place of writing the binding, crippy's
// certificate check and the session handling itself (crippy D114). Nothing is written yet.
android {
    namespace = "com.systemhalted.crippy.client"
    compileSdk = versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
    // A published library's public surface is declared, not inferred (L1).
    explicitApi()
}

dependencies {
    // `api`: a client's own types will name the contract's, so an app that sees the client sees them.
    api(project(":contract"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Higher than crippy gives :provider (L4): this module will carry the check that the service bound
// to is crippy, which is the code a reviewer reads first.
kover {
    reports {
        verify {
            rule("L4: line coverage") { minBound(90, CoverageUnit.LINE) }
            rule("L4: branch coverage") { minBound(85, CoverageUnit.BRANCH) }
        }
    }
}

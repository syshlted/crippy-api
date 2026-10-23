import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// Kotlin support is built into AGP since 9.0; applying org.jetbrains.kotlin.android
// alongside it is an error, not a redundancy.
plugins {
    alias(libs.plugins.android.library)
}

val versions = libs.versions

// The client library (L1): what an app integrates in place of writing the binding, crippy's
// certificate check and the session handling itself (crippy D114). Written at L12.
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
    // `implementation`, so no AIDL type reaches an app's compile classpath (L5): an app codes against
    // the client's own types, and only the client can call the contract. It still arrives at runtime.
    implementation(project(":crippy-contract"))

    // `api`, because a CrippySession's requests are suspend functions and its events a SharedFlow (L12):
    // an app that calls them has to compile against the types they name.
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)

    // A real Binder, Parcel and PackageManager off a device, as the contract's tests have (L8). JUnit 4
    // runs them under the vintage engine beside the JUnit 5 cases, in one task.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // See crippy-contract's build: Robolectric at SDK 36 on JDK 21 (L8).
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

android {
    testOptions {
        // Robolectric reads the merged manifest and resources: the client's <queries> is what lets an app
        // see crippy at API 30 and above.
        unitTests.isIncludeAndroidResources = true
    }
}

// Higher than crippy gives :provider (L4): this module carries the check that the service bound
// to is crippy, which is the code a reviewer reads first.
kover {
    reports {
        verify {
            rule("L4: line coverage") { minBound(90, CoverageUnit.LINE) }
            rule("L4: branch coverage") { minBound(85, CoverageUnit.BRANCH) }
        }
    }
}

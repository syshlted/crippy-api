import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// Kotlin support is built into AGP since 9.0; applying org.jetbrains.kotlin.android
// alongside it is an error, not a redundancy.
plugins {
    alias(libs.plugins.android.library)
}

val versions = libs.versions

// The contract (L1, L2): the .aidl files, which are the wire specification, and the hand-written
// types they name. crippy's service implements it and every client calls it, so nothing here may
// depend on either side.
android {
    namespace = "com.systemhalted.crippy.api"
    compileSdk = versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = versions.minSdk.get().toInt()
    }

    buildFeatures {
        aidl = true
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
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Robolectric is a JUnit4 runner and the rest of the suite is JUnit5, so the vintage engine runs
    // both under one Gradle task, as in crippy (crippy D13): two tasks would leave `allUnitTests`
    // quietly covering one of them. It is here because the generated proxy needs a real Parcel (L8).
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Robolectric at SDK 36 sets up shared memory by writing a FileDescriptor's fd through
    // jdk.internal.access.SharedSecrets, which JDK 21 does not export: without this every SDK 36 case
    // fails before it starts, with "Failed to interact with raw FileDescriptor internals" (L8).
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

// The gate measures what was written here, never what the aidl tool generated from it (L4): a stub
// and proxy are the tool's code, and counting them would make the number say how many methods the
// contract has. Everything that implements a Binder interface is generated, so that is the filter.
kover {
    reports {
        filters {
            excludes {
                inheritedFrom("android.os.IInterface")
            }
        }
        verify {
            rule("L4: line coverage") { minBound(90, CoverageUnit.LINE) }
        }
    }
}

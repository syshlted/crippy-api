import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// Kotlin support is built into AGP since 9.0; applying org.jetbrains.kotlin.android
// alongside it is an error, not a redundancy.
plugins {
    alias(libs.plugins.android.library)
}

val versions = libs.versions

// The client library (L1): what an app integrates in place of writing the binding, encri's
// certificate check and the session handling itself (encri D114). Written at L12.
android {
    namespace = "com.systemhalted.encri.client"
    compileSdk = versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = versions.minSdk.get().toInt()
        // The device tests (L13): the client over a real Binder, against a stand-in in another process and
        // against encri itself.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(project(":encri-contract"))

    // `api`, because an EncriSession's requests are suspend functions and its events a SharedFlow (L12):
    // an app that calls them has to compile against the types they name.
    api(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":api-dump"))

    // A real Binder, Parcel and PackageManager off a device, as the contract's tests have (L8). JUnit 4
    // runs them under the vintage engine beside the JUnit 5 cases, in one task.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // See encri-contract's build: Robolectric at SDK 36 on JDK 21 (L8).
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    // The API dump (L16): compared on every run, and rewritten with -PupdateApi.
    inputs.files(fileTree("api")).withPropertyName("apiDump")
    systemProperty("encri.api.dump", file("api/encri-client.api").absolutePath)
    systemProperty("encri.api.update", providers.gradleProperty("updateApi").isPresent.toString())
}

android {
    testOptions {
        // Robolectric reads the merged manifest and resources: the client's <queries> is what lets an app
        // see encri at API 30 and above.
        unitTests.isIncludeAndroidResources = true
    }
}

// Higher than encri gives :provider (L4): this module carries the check that the service bound
// to is encri, which is the code a reviewer reads first.
kover {
    reports {
        verify {
            rule("L4: line coverage") { minBound(90, CoverageUnit.LINE) }
            rule("L4: branch coverage") { minBound(85, CoverageUnit.BRANCH) }
        }
    }
}

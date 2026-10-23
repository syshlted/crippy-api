import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// The registry of recognized apps (L15, encri D114): its parser, the rules every entry is held to, and
// the snapshot encri builds in. Pure JVM, as L1 foresaw for the first module that needs no Android: it
// reads text and compares strings. The Kotlin plugin is on the root build's classpath (L9), so it is
// applied here by id.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
    // A published library's public surface is declared, not inferred (L1).
    explicitApi()
}

// What encri receives from this module, locked as the others' release classpaths are (L10).
configurations.runtimeClasspath {
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":api-dump"))
}

// The snapshot: every registry/*.app, in name order, in one resource. It is what an encri built against
// this artifact recognizes, with no network, until the next release carries the next snapshot (encri D114).
val registryApps = rootProject.fileTree("registry") { include("*.app") }
val snapshotDir = layout.buildDirectory.dir("generated/registry-snapshot")
val registrySnapshot =
    tasks.register("registrySnapshot") {
        description = "Packs registry/*.app into the jar, as the snapshot encri builds in (L15)."
        inputs.files(registryApps).withPathSensitivity(PathSensitivity.NAME_ONLY)
        outputs.dir(snapshotDir)
        doLast {
            val target = snapshotDir.get().file("com/systemhalted/encri/registry/snapshot.txt").asFile
            target.parentFile.mkdirs()
            target.writeText(
                registryApps.files
                    .sortedBy { it.name }
                    .joinToString("") { "%% ${it.name}\n${it.readText()}\n" },
            )
        }
    }

sourceSets.main {
    resources.srcDir(registrySnapshot)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The registry this repository holds, which a test validates on every run: that is the check CI makes
    // of every pull request that adds an app (L15).
    systemProperty("encri.registry.dir", rootProject.file("registry").absolutePath)
    // The API dump (L16): compared on every run, and rewritten with -PupdateApi.
    inputs.files(fileTree("api")).withPropertyName("apiDump")
    systemProperty("encri.api.dump", file("api/encri-registry.api").absolutePath)
    systemProperty("encri.api.update", providers.gradleProperty("updateApi").isPresent.toString())
}

// For a pull request's author: every entry checked, and each one said back.
tasks.register<JavaExec>("validateRegistry") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks every registry/*.app against the rules encri holds an entry to (L15)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.systemhalted.encri.registry.ValidateRegistryKt")
    args(rootProject.file("registry").absolutePath)
}

kover {
    reports {
        verify {
            rule("L4: line coverage") { minBound(90, CoverageUnit.LINE) }
            rule("L4: branch coverage") { minBound(85, CoverageUnit.BRANCH) }
        }
    }
}

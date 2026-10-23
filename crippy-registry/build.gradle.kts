import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// The registry of recognized apps (L15, crippy D114): its parser, the rules every entry is held to, and
// the snapshot crippy builds in. Pure JVM, as L1 foresaw for the first module that needs no Android: it
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

// What crippy receives from this module, locked as the others' release classpaths are (L10).
configurations.runtimeClasspath {
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The snapshot: every registry/*.app, in name order, in one resource. It is what a crippy built against
// this artifact recognizes, with no network, until the next release carries the next snapshot (crippy D114).
val registryApps = rootProject.fileTree("registry") { include("*.app") }
val snapshotDir = layout.buildDirectory.dir("generated/registry-snapshot")
val registrySnapshot =
    tasks.register("registrySnapshot") {
        description = "Packs registry/*.app into the jar, as the snapshot crippy builds in (L15)."
        inputs.files(registryApps).withPathSensitivity(PathSensitivity.NAME_ONLY)
        outputs.dir(snapshotDir)
        doLast {
            val target = snapshotDir.get().file("com/systemhalted/crippy/registry/snapshot.txt").asFile
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
    systemProperty("crippy.registry.dir", rootProject.file("registry").absolutePath)
}

// For a pull request's author: every entry checked, and each one said back.
tasks.register<JavaExec>("validateRegistry") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks every registry/*.app against the rules crippy holds an entry to (L15)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.systemhalted.crippy.registry.ValidateRegistryKt")
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

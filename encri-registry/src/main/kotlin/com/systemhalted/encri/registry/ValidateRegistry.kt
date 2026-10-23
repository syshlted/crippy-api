package com.systemhalted.encri.registry

import java.io.File
import kotlin.system.exitProcess

/**
 * `./gradlew :encri-registry:validateRegistry`: every `.app` file in `registry/` checked, and each one said
 * back, for the author of a pull request that adds one (L15). CI runs the same rules as a unit test.
 */
public fun main(args: Array<String>) {
    try {
        println(validate(File(args.single())))
    } catch (wrong: RegistryException) {
        System.err.println("registry: ${wrong.message}")
        exitProcess(1)
    }
}

/** The registry in [dir], checked, as a reviewer reads it back: one line per app. */
internal fun validate(dir: File): String {
    val files = dir.listFiles { file -> file.name.endsWith(".app") }.orEmpty().associate { it.name to it.readText() }
    val registry = Registry.of(files)
    return if (registry.apps.isEmpty()) {
        "registry: no apps listed"
    } else {
        registry.apps.joinToString("\n") { app ->
            "${app.packageName}: by ${app.developer}, ${app.certificates.size} certificate(s), " +
                "rights ${app.rights.joinToString(" ") { it.word }}, from version ${app.minimumVersion}" +
                if (app.refusedVersions.isEmpty()) "" else ", refusing ${app.refusedVersions.sorted().joinToString()}"
        }
    }
}

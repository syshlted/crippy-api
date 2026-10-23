package com.systemhalted.encri.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/**
 * L5's guard: no public or protected signature in the client names the contract, so an app compiles
 * against the client's own types and nothing else. Kotlin does not stop a public signature naming a type
 * from an `implementation` dependency, and an app would then fail to compile against it with
 * "cannot access class".
 *
 * Reads the compiled classes of the client's own package: its subpackage `internal` is where the
 * contract is spoken, and is skipped.
 */
class PublicSurfaceTest {
    @Test
    fun `no public signature in the client names the contract`() {
        val leaks =
            publicClasses().flatMap { type ->
                val methods =
                    type.declaredMethods.filter { visible(it.modifiers) && !it.isSynthetic }.flatMap { method ->
                        (method.genericParameterTypes.toList() + method.genericReturnType).map {
                            "${type.simpleName}.${method.name}: $it"
                        }
                    }
                val constructors =
                    type.declaredConstructors.filter { visible(it.modifiers) && !it.isSynthetic }.flatMap { made ->
                        made.genericParameterTypes.map { "${type.simpleName}(): $it" }
                    }
                val fields =
                    type.declaredFields.filter { visible(it.modifiers) }.map {
                        "${type.simpleName}.${it.name}: ${it.genericType}"
                    }
                val members = methods + constructors + fields
                members.filter { CONTRACT in it }
            }
        assertEquals(emptyList<String>(), leaks)
    }

    @Test
    fun `the scan sees the client's classes at all`() {
        // Without this, a scan that found nothing would pass the case above.
        assertTrue(publicClasses().any { it == EncriSession::class.java })
    }

    private fun visible(modifiers: Int) = Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)

    /**
     * The public classes directly in the client's package. AGP hands a unit test the module's classes
     * as a directory or as a jar depending on the build, so both are read.
     */
    private fun publicClasses(): List<Class<*>> {
        val root =
            File(
                EncriSession::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val path = PACKAGE.replace('.', '/') + "/"
        val names =
            if (root.isDirectory) {
                File(root, path).listFiles { file -> file.isFile }.orEmpty().map { it.name }
            } else {
                ZipFile(root).use { jar ->
                    jar
                        .entries()
                        .toList()
                        .map { it.name }
                        .filter { it.startsWith(path) }
                        .map { it.removePrefix(path) }
                }
            }
        return names
            .filter { it.endsWith(".class") && '/' !in it }
            .map { Class.forName("$PACKAGE.${it.removeSuffix(".class")}") }
            // An anonymous object — a listener handed to encri — is public in bytecode and unnameable
            // from an app's source, so it is no part of the surface.
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isAnonymousClass }
    }

    private companion object {
        const val PACKAGE = "com.systemhalted.encri.client"
        const val CONTRACT = "com.systemhalted.encri.api"
    }
}

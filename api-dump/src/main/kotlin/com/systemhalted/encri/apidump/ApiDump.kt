package com.systemhalted.encri.apidump

import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KVariance
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.jvm.kotlinFunction

/**
 * The public API of one package, as an app compiling against it sees it (L16): every public and protected
 * class, constructor, function and property, with parameter names and which ones have defaults, in a
 * stable order. Compared against a dump the module commits, so a change to what an app compiles against
 * is a diff somebody reads before a release rather than a breakage somebody meets after one.
 *
 * **Kotlin's own view, through kotlin-reflect**, because the bytecode cannot tell `internal` from public:
 * an internal declaration is public there, with a mangled name or none. Kotlin's ABI validation would be
 * the standard tool and does not see AGP's built-in Kotlin yet (L16).
 *
 * Shared by every module's tests from `config/api-dump`, so the rules are one copy.
 */
@Suppress("TooManyFunctions") // One per kind of declaration a dump describes.
object ApiDump {
    /** The dump of [packageName], read from the classes beside [anchor]. Subpackages are not included. */
    fun of(
        anchor: Class<*>,
        packageName: String,
    ): String {
        val classes = classNames(anchor, packageName).map { Class.forName("$packageName.$it") }
        val facades = classes.filter { it.getAnnotation(Metadata::class.java)?.kind == FILE_FACADE }
        val types =
            classes
                .filter { it.getAnnotation(Metadata::class.java)?.kind == CLASS && it.enclosingClass == null }
                .map { it.kotlin }
                .filter(::visible)
        return (
            types.sortedBy { it.qualifiedName }.flatMap(::describe) +
                facades.sortedBy { it.name }.flatMap(::describeFacade)
        ).joinToString("\n", postfix = "\n")
    }

    /**
     * Compares [actual] with the dump at [path], or writes it there when [update] is set. Answers what is
     * wrong, or null: a test asserts the answer is null, so its failure says what changed.
     */
    fun check(
        actual: String,
        path: String,
        update: Boolean,
    ): String? {
        val file = File(path)
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
        }
        val expected = if (file.isFile) file.readText() else ""
        return if (expected == actual) null else difference(file.name, expected, actual)
    }

    private fun difference(
        name: String,
        expected: String,
        actual: String,
    ): String {
        val before = expected.lines().toSet()
        val after = actual.lines().toSet()
        val removed = expected.lines().filter { it !in after }.map { "- $it" }
        val added = actual.lines().filter { it !in before }.map { "+ $it" }
        return (
            listOf(
                "The public API is not the one in $name. If the change is meant, review it as a change to what",
                "every app compiles against, then write the new dump with -PupdateApi:",
            ) + removed + added
        ).joinToString("\n")
    }

    private fun describe(type: KClass<*>): List<String> {
        val header = "${kindOf(type)} ${type.qualifiedName}${typeParametersOf(type)}${supertypesOf(type)}"
        val constructors = type.constructors.filter(::visible).map { "  constructor(${parametersOf(it)})" }
        val members = type.declaredMembers.filter(::visible).map { "  ${memberOf(it)}" }
        val entries =
            type.java.enumConstants
                ?.map { "  entry ${(it as Enum<*>).name}" }
                .orEmpty()
        val nested =
            type.nestedClasses
                .filter(::visible)
                .sortedBy { it.qualifiedName }
                .flatMap(::describe)
        return listOf(header) + entries + (constructors + members).sorted() + nested
    }

    /**
     * Top-level functions, which Kotlin compiles into a `…Kt` class reflection cannot take as a class: each
     * method is mapped back to its Kotlin function, whose visibility the bytecode does not keep — an
     * internal top-level function is public and unmangled there.
     */
    private fun describeFacade(facade: Class<*>): List<String> =
        facade.declaredMethods
            .filter { Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .mapNotNull { it.kotlinFunction }
            .filter(::visible)
            .map { "top-level ${memberOf(it)}" }
            .sorted()

    private fun kindOf(type: KClass<*>): String {
        val kind =
            when {
                type.isCompanion -> "companion object"
                type.objectInstance != null && type.isData -> "data object"
                type.objectInstance != null -> "object"
                type.java.isEnum -> "enum class"
                type.java.isInterface -> interfaceKind(type)
                type.isData -> "data class"
                type.isSealed -> "sealed class"
                type.isAbstract -> "abstract class"
                type.isOpen -> "open class"
                else -> "class"
            }
        return "${visibilityOf(type.visibility)} $kind"
    }

    private fun interfaceKind(type: KClass<*>): String =
        when {
            type.isFun -> "fun interface"
            type.isSealed -> "sealed interface"
            else -> "interface"
        }

    /** A class's type parameters with their variance and bounds: changing either changes what compiles. */
    private fun typeParametersOf(type: KClass<*>): String {
        if (type.typeParameters.isEmpty()) return ""
        return type.typeParameters.joinToString(prefix = "<", postfix = ">") { parameter ->
            val variance =
                if (parameter.variance ==
                    KVariance.INVARIANT
                ) {
                    ""
                } else {
                    parameter.variance.name.lowercase() + " "
                }
            val bounds = parameter.upperBounds.map { it.toString() }.filterNot { it == "kotlin.Any?" }
            variance + parameter.name + if (bounds.isEmpty()) "" else " : " + bounds.joinToString(" & ")
        }
    }

    private fun supertypesOf(type: KClass<*>): String {
        val supertypes =
            type.supertypes.map { it.toString() }.filterNot { it == "kotlin.Any" || it.startsWith("kotlin.Enum<") }
        return if (supertypes.isEmpty()) "" else " : " + supertypes.sorted().joinToString()
    }

    private fun memberOf(member: KCallable<*>): String {
        val prefix = visibilityOf(member.visibility)
        return when (member) {
            is KMutableProperty<*> -> {
                "$prefix var ${member.name}: ${member.returnType}"
            }

            is KProperty<*> -> {
                "$prefix val ${member.name}: ${member.returnType}"
            }

            is KFunction<*> -> {
                val suspend = if (member.isSuspend) "suspend " else ""
                val receiver = member.parameters.firstOrNull { it.kind == KParameter.Kind.EXTENSION_RECEIVER }
                val on = receiver?.let { "${it.type}." }.orEmpty()
                "$prefix ${suspend}fun $on${member.name}(${parametersOf(member)}): ${member.returnType}"
            }

            else -> {
                "$prefix ${member.name}"
            }
        }
    }

    private fun parametersOf(function: KFunction<*>): String =
        function.parameters
            .filter { it.kind == KParameter.Kind.VALUE }
            .joinToString {
                "${if (it.isVararg) "vararg " else ""}${it.name}: ${it.type}${if (it.isOptional) " = …" else ""}"
            }

    private fun visible(type: KClass<*>): Boolean =
        type.visibility == KVisibility.PUBLIC || type.visibility == KVisibility.PROTECTED

    private fun visible(member: KCallable<*>): Boolean =
        member.visibility == KVisibility.PUBLIC || member.visibility == KVisibility.PROTECTED

    private fun visibilityOf(visibility: KVisibility?): String = visibility?.name?.lowercase() ?: "public"

    /** The classes directly in the package, from a directory or a jar, as AGP and Gradle hand either. */
    private fun classNames(
        anchor: Class<*>,
        packageName: String,
    ): List<String> {
        val root =
            File(
                anchor.protectionDomain.codeSource.location
                    .toURI(),
            )
        val path = packageName.replace('.', '/') + "/"
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
        return names.filter { it.endsWith(".class") && '/' !in it }.map { it.removeSuffix(".class") }
    }

    private const val CLASS = 1
    private const val FILE_FACADE = 2
}

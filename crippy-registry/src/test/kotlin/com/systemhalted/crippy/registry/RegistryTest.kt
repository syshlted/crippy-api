package com.systemhalted.crippy.registry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The registry (L15): the format every entry is held to, what each answer means, and the registry this
 * repository actually holds, which is checked here on every run — that is the validator CI runs on every
 * pull request that adds an app.
 */
class RegistryTest {
    @Test
    fun `the registry in this repository is valid, and is what the jar carries`() {
        val dir = File(requireNotNull(System.getProperty("crippy.registry.dir")) { "Gradle names the registry" })
        val files =
            dir
                .listFiles { file ->
                    file.name.endsWith(".app")
                }.orEmpty()
                .associate { it.name to it.readText() }
        val checked = Registry.of(files)
        assertEquals(checked.apps, Registry.bundled().apps, "the snapshot is not the registry")
        assertTrue(validate(dir).isNotEmpty())
    }

    /** The real registry is empty until an app has a release key, so the snapshot's own reading is shown here. */
    @Test
    fun `a snapshot of two apps reads back as two apps`() {
        val other = ENTRY.replace(PACKAGE, "com.example.notes")
        val registry = Registry.ofSnapshot("%% com.example.notes.app\n$other\n%% $FILE\n$ENTRY\n")
        assertEquals(listOf("com.example.files", "com.example.notes"), registry.apps.map { it.packageName })
        assertEquals(Registry.of(mapOf(FILE to ENTRY)).apps.single(), registry.apps.first())
        assertEquals(emptyList<RecognizedApp>(), Registry.ofSnapshot("").apps)
    }

    @Test
    fun `an entry reads back as written`() {
        val app = RegistryFormat.parse(FILE, ENTRY)
        assertEquals(
            RecognizedApp(
                packageName = PACKAGE,
                developer = "Example Ltd",
                source = "https://github.com/example/files",
                certificates = setOf(KEY, ROTATED),
                rights = setOf(RecognizedRight.READ, RecognizedRight.WRITE, RecognizedRight.CREATE),
                minimumVersion = 12,
                refusedVersions = setOf(14, 15),
            ),
            app,
        )
    }

    @Test
    fun `a certificate is read as apksigner prints it, or with colons and capitals`() {
        val colons = KEY.uppercase().chunked(2).joinToString(":")
        val app = RegistryFormat.parse(FILE, ENTRY.replace("certificate: $KEY", "certificate: $colons"))
        assertTrue(KEY in app.certificates)
    }

    @Test
    fun `a listed app signed with a listed key is reviewed, and what it asks beyond review is named`() {
        val registry = Registry.of(mapOf(FILE to ENTRY))
        val asked = setOf(RecognizedRight.READ, RecognizedRight.DELETE)
        assertEquals(
            Recognition.Reviewed(registry.apps.single(), setOf(RecognizedRight.DELETE)),
            registry.recognize(PACKAGE, KEY, versionCode = 13, rights = asked),
        )
        val reviewed =
            registry.recognize(
                PACKAGE,
                ROTATED.uppercase(),
                versionCode = 20,
                rights = setOf(RecognizedRight.READ),
            )
        assertEquals(emptySet<RecognizedRight>(), (reviewed as Recognition.Reviewed).unreviewedRights)
    }

    @Test
    fun `the same package signed with another key is not reviewed`() {
        val registry = Registry.of(mapOf(FILE to ENTRY))
        assertEquals(
            Recognition.OtherKey(registry.apps.single()),
            registry.recognize(PACKAGE, "0".repeat(64), 13, READ),
        )
    }

    @Test
    fun `a version below the floor or refused is refused`() {
        val registry = Registry.of(mapOf(FILE to ENTRY))
        val app = registry.apps.single()
        assertEquals(Recognition.Refused(app, 11), registry.recognize(PACKAGE, KEY, 11, READ))
        assertEquals(Recognition.Refused(app, 14), registry.recognize(PACKAGE, KEY, 14, READ))
        assertTrue(registry.recognize(PACKAGE, KEY, 16, READ) is Recognition.Reviewed)
    }

    @Test
    fun `an app not on the registry is not listed`() {
        assertEquals(
            Recognition.NotListed,
            Registry.of(mapOf(FILE to ENTRY)).recognize("com.example.other", KEY, 1, READ),
        )
    }

    @Test
    fun `refusals name the line that is wrong`() {
        assertRefused("unknown key `sorce`", ENTRY.replace("source:", "sorce:"))
        assertRefused("`developer` appears twice", ENTRY + "developer: Somebody Else\n")
        assertRefused("not a `key: value` line", ENTRY + "just words\n")
        assertRefused("`rights` has no value", ENTRY.replace("rights: read write create", "rights:"))
        assertRefused("missing source", ENTRY.lines().filterNot { it.startsWith("source") }.joinToString("\n"))
    }

    @Test
    fun `refusals name the value that is wrong`() {
        assertRefused("is not a package name", ENTRY.replace("package: $PACKAGE", "package: files"), "files.app")
        assertRefused(
            "is not a package name",
            ENTRY.replace("package: $PACKAGE", "package: com.9files"),
            "com.9files.app",
        )
        assertRefused("the file is named", ENTRY, "com.example.notes.app")
        assertRefused("is not an https address", ENTRY.replace("https://github.com", "http://github.com"))
        assertRefused("is not a SHA-256 in hex", ENTRY.replace(KEY, KEY.dropLast(1)))
        assertRefused("`erase` is not a right", ENTRY.replace("read write create", "read erase"))
        assertRefused("every app is reviewed for `read`", ENTRY.replace("read write create", "write"))
        assertRefused(
            "`minimum-version` is not a version code",
            ENTRY.replace("minimum-version: 12", "minimum-version: 0"),
        )
        assertRefused("`fifteen` is not a version code", ENTRY.replace("14, 15", "14, fifteen"))
    }

    @Test
    fun `an app listed twice refuses the whole registry`() {
        val wrong = assertThrows<RegistryException> { Registry.of(mapOf(FILE to ENTRY, "z.app" to ENTRY)) }
        assertTrue("z.app" in wrong.message.orEmpty() || "listed more than once" in wrong.message.orEmpty())
    }

    @Test
    fun `the validator says back each app, and says when there are none`(
        @TempDir dir: File,
    ) {
        assertEquals("registry: no apps listed", validate(dir))
        File(dir, FILE).writeText(ENTRY)
        File(dir, "README.md").writeText("not an entry")
        assertEquals(
            "$PACKAGE: by Example Ltd, 2 certificate(s), rights read write create, from version 12, refusing 14, 15",
            validate(dir),
        )
        File(dir, FILE).writeText(ENTRY.lines().filterNot { it.startsWith("refused") }.joinToString("\n"))
        assertTrue(validate(dir).endsWith("from version 12"))
    }

    private fun assertRefused(
        why: String,
        text: String,
        fileName: String = FILE,
    ) {
        val wrong = assertThrows<RegistryException> { RegistryFormat.parse(fileName, text) }
        assertTrue(why in wrong.message.orEmpty(), "expected “$why” in “${wrong.message}”")
    }

    private companion object {
        const val PACKAGE = "com.example.files"
        const val FILE = "$PACKAGE.app"
        val KEY = "3f".repeat(32)
        val ROTATED = "a0".repeat(32)
        val READ = setOf(RecognizedRight.READ)
        val ENTRY =
            """
            # An example, as a pull request would add one.
            package: $PACKAGE
            developer: Example Ltd
            source: https://github.com/example/files
            certificate: $KEY
            certificate: $ROTATED

            rights: read write create
            minimum-version: 12
            refused-versions: 14, 15
            """.trimIndent() + "\n"
    }
}

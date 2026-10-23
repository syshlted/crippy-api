package com.systemhalted.encri.registry

/**
 * A registry file, `registry/<package>.app` (L15): one `key: value` per line, `#` for a comment line,
 * blank lines ignored.
 *
 * ```
 * package: com.example.files
 * developer: Example Ltd
 * source: https://github.com/example/files
 * certificate: 3f1c…64 hex digits…
 * rights: read write create
 * minimum-version: 12
 * refused-versions: 14, 15
 * ```
 *
 * **Strict, because a mistake here is a label on a stranger's app.** An unknown key is refused rather
 * than ignored, so a misspelt `refused-versions` cannot quietly refuse nothing; every key but
 * `certificate` appears once; and every value is checked for its form. The file's name must be its
 * package's, so a pull request cannot list one app under another's name.
 */
internal object RegistryFormat {
    private const val SUFFIX = ".app"
    private const val SHA256_HEX = 64

    private val KEYS =
        setOf("package", "developer", "source", "certificate", "rights", "minimum-version", "refused-versions")
    private val REPEATABLE = setOf("certificate")
    private val REQUIRED = KEYS - "refused-versions"

    /** One segment of an Android package name. */
    private val SEGMENT = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val HEX = Regex("[0-9a-f]{$SHA256_HEX}")
    private val HOST = Regex("https://[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/\\S*)?")

    fun parse(
        fileName: String,
        text: String,
    ): RecognizedApp {
        val values = linkedMapOf<String, MutableList<String>>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val colon = line.indexOf(':')

            fun fail(why: String): Nothing = throw RegistryException("$fileName:${index + 1}: $why")
            if (colon <= 0) fail("not a `key: value` line")
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key !in KEYS) fail("unknown key `$key`")
            if (value.isEmpty()) fail("`$key` has no value")
            val seen = values.getOrPut(key) { mutableListOf() }
            if (seen.isNotEmpty() && key !in REPEATABLE) fail("`$key` appears twice")
            seen += value
        }
        val missing = REQUIRED - values.keys
        if (missing.isNotEmpty()) throw RegistryException("$fileName: missing ${missing.sorted().joinToString()}")
        return check(fileName, values)
    }

    private fun check(
        fileName: String,
        values: Map<String, List<String>>,
    ): RecognizedApp {
        fun fail(why: String): Nothing = throw RegistryException("$fileName: $why")

        fun one(key: String): String = values.getValue(key).single()

        val packageName = one("package")
        val segments = packageName.split('.')
        if (segments.size < 2 || !segments.all(SEGMENT::matches)) fail("`$packageName` is not a package name")
        if (fileName != packageName + SUFFIX) fail("the file is named `$fileName` and lists `$packageName`")

        val source = one("source")
        if (!HOST.matches(source)) fail("`$source` is not an https address")

        val certificates = values.getValue("certificate").map { it.replace(":", "").lowercase() }
        certificates.firstOrNull { !HEX.matches(it) }?.let { fail("`$it` is not a SHA-256 in hex") }

        val words = one("rights").split(Regex("\\s+"))
        val rights =
            words.map { word ->
                RecognizedRight.entries.firstOrNull { it.word == word }
                    ?: fail("`$word` is not a right")
            }
        if (RecognizedRight.READ !in rights) fail("every app is reviewed for `read`: every grant carries it")

        val minimum =
            one("minimum-version").toLongOrNull()?.takeIf { it > 0 } ?: fail("`minimum-version` is not a version code")
        val refused =
            values["refused-versions"]
                ?.single()
                ?.split(',')
                ?.map { word ->
                    word.trim().toLongOrNull()?.takeIf { it > 0 } ?: fail("`${word.trim()}` is not a version code")
                }.orEmpty()

        return RecognizedApp(
            packageName = packageName,
            developer = one("developer"),
            source = source,
            certificates = certificates.toSet(),
            rights = rights.toSet(),
            minimumVersion = minimum,
            refusedVersions = refused.toSet(),
        )
    }
}

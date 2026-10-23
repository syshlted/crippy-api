package com.systemhalted.crippy.registry

/**
 * The apps crippy recognizes (crippy D114, D115; L15): who wrote each, where its source is, and the key it
 * is signed with. A pull request adds one; every crippy release builds in the snapshot of the release of
 * this library it was built against, so recognizing an app needs no network.
 *
 * **Recognition never grants access.** It changes what crippy's consent screen says about an app, and
 * nothing a user decides: an app that is not listed can still be let in, without the label. The one
 * thing an entry can take away is a version it refuses, which crippy does not let in at all.
 */
public class Registry private constructor(
    /** Every app listed, in package order. */
    public val apps: List<RecognizedApp>,
) {
    /**
     * What crippy should say about an app asking to be let in: [packageName], signed with a certificate
     * whose SHA-256 is [certificateSha256] as crippy computes it, at [versionCode], asking for [rights].
     */
    public fun recognize(
        packageName: String,
        certificateSha256: String,
        versionCode: Long,
        rights: Set<RecognizedRight>,
    ): Recognition {
        val app = apps.firstOrNull { it.packageName == packageName } ?: return Recognition.NotListed
        return when {
            certificateSha256.lowercase() !in app.certificates -> {
                Recognition.OtherKey(app)
            }

            versionCode < app.minimumVersion || versionCode in app.refusedVersions -> {
                Recognition.Refused(
                    app,
                    versionCode,
                )
            }

            else -> {
                Recognition.Reviewed(app, rights - app.rights)
            }
        }
    }

    public companion object {
        private const val SNAPSHOT = "/com/systemhalted/crippy/registry/snapshot.txt"
        private const val SEPARATOR = "%% "

        /** The registry built into this artifact: what a crippy built against it recognizes. */
        public fun bundled(): Registry {
            val text =
                checkNotNull(Registry::class.java.getResourceAsStream(SNAPSHOT)) { "this build carries no registry" }
                    .bufferedReader()
                    .use { it.readText() }
            return ofSnapshot(text)
        }

        /** A registry read back from a snapshot as the build writes one: each file after a `%% <name>` line. */
        internal fun ofSnapshot(snapshot: String): Registry = of(split(snapshot))

        /** A registry of [files], each file's name and text, every one checked; any that is wrong refuses all. */
        public fun of(files: Map<String, String>): Registry {
            val apps = files.entries.sortedBy { it.key }.map { (name, text) -> RegistryFormat.parse(name, text) }
            val repeated = apps.groupBy { it.packageName }.filterValues { it.size > 1 }.keys
            if (repeated.isNotEmpty()) {
                throw RegistryException(
                    "listed more than once: ${repeated.sorted().joinToString()}",
                )
            }
            return Registry(apps.sortedBy { it.packageName })
        }

        private fun split(snapshot: String): Map<String, String> {
            val files = linkedMapOf<String, StringBuilder>()
            var current: StringBuilder? = null
            for (line in snapshot.lines()) {
                if (line.startsWith(SEPARATOR)) {
                    current = StringBuilder().also { files[line.removePrefix(SEPARATOR)] = it }
                } else {
                    current?.appendLine(line)
                }
            }
            return files.mapValues { it.value.toString() }
        }
    }
}

/** One app crippy recognizes. */
public data class RecognizedApp(
    /** Its package, which is also its file's name, `<package>.app`. */
    val packageName: String,
    /** Who wrote it, as the consent screen names them. */
    val developer: String,
    /** Where its source is: what a reviewer read, and what a user can. */
    val source: String,
    /**
     * The SHA-256 of the certificate it is signed with, in lower-case hex, as `apksigner verify
     * --print-certs` prints it. More than one when a key has been rotated and both builds are recognized.
     */
    val certificates: Set<String>,
    /** The rights it was reviewed for; anything more it asks for is said to be unreviewed. */
    val rights: Set<RecognizedRight>,
    /** The lowest version code recognized. */
    val minimumVersion: Long,
    /** Version codes refused outright: builds known to be bad, which crippy does not let in. */
    val refusedVersions: Set<Long>,
)

/** What an app may do in the vault, as the contract's rights name them (crippy-api L11). */
public enum class RecognizedRight(
    /** Its name in a registry file. */
    public val word: String,
) {
    READ("read"),
    WRITE("write"),
    CREATE("create"),
    DELETE("delete"),
    MOVE("move"),
}

/** What crippy says about an app, by what the registry holds for it. */
public sealed interface Recognition {
    /** Listed, signed with a listed key, at a version not refused. [unreviewedRights] are asked for beyond review. */
    public data class Reviewed(
        val app: RecognizedApp,
        val unreviewedRights: Set<RecognizedRight>,
    ) : Recognition

    /**
     * Listed, and signed with a key the registry does not name: a lookalike using the package's name, or a
     * build somebody made from the source and signed themselves. crippy says which it cannot tell.
     */
    public data class OtherKey(
        val app: RecognizedApp,
    ) : Recognition

    /** Listed, signed with a listed key, at a version the registry refuses or below its floor. */
    public data class Refused(
        val app: RecognizedApp,
        val versionCode: Long,
    ) : Recognition

    /** Not on the registry: crippy knows nothing about who wrote it. */
    public data object NotListed : Recognition
}

/** A registry file that does not follow the format, or a registry that lists an app twice. */
public class RegistryException(
    message: String,
) : Exception(message)

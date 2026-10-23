package com.systemhalted.encri.client

/**
 * Which signing keys this app accepts encri being signed by (encri D114, D115; L12).
 *
 * Checked before anything is bound, because an app that sends a lookalike its documents has given them
 * away (encri D114). Two ways to trust a key, and either is enough:
 *
 * - **[certificates]**: SHA-256 digests of signing certificates, in lower-case hex, as
 *   `apksigner verify --print-certs` prints them. [RELEASE_CERTIFICATES] holds encri's own.
 * - **[sameSigner]**: encri is signed by the same key as this app. encri's file manager is signed with
 *   encri's key (encri D115), so this is what lets it trust a build of encri made on the same machine
 *   as itself, debug builds included, without naming a key.
 */
public class EncriTrust private constructor(
    public val certificates: Set<String>,
    public val sameSigner: Boolean,
) {
    public companion object {
        /**
         * The certificates encri's releases are signed with. **Empty until encri has a release key**
         * (encri D115): no build signed by anyone is trusted on these grounds alone until then.
         */
        public val RELEASE_CERTIFICATES: Set<String> = emptySet()

        /** encri's release certificates, and an encri signed by the same key as this app. */
        public val DEFAULT: EncriTrust = EncriTrust(RELEASE_CERTIFICATES, sameSigner = true)

        /**
         * [certificates] as well as encri's release certificates. For an app developed against an encri
         * built and signed somewhere else, such as a debug build.
         */
        public fun alsoTrusting(
            vararg certificates: String,
            sameSigner: Boolean = true,
        ): EncriTrust {
            val digests = certificates.map { it.lowercase().replace(":", "") }
            require(digests.all { it.length == SHA256_HEX && it.all(::isHexDigit) }) {
                "a certificate is named by its SHA-256, 64 hex digits"
            }
            return EncriTrust(RELEASE_CERTIFICATES + digests, sameSigner)
        }

        private const val SHA256_HEX = 64

        private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'
    }
}

package com.systemhalted.crippy.client

/**
 * Which signing keys this app accepts crippy being signed by (crippy D114, D115; L12).
 *
 * Checked before anything is bound, because an app that sends a lookalike its documents has given them
 * away (crippy D114). Two ways to trust a key, and either is enough:
 *
 * - **[certificates]**: SHA-256 digests of signing certificates, in lower-case hex, as
 *   `apksigner verify --print-certs` prints them. [RELEASE_CERTIFICATES] holds crippy's own.
 * - **[sameSigner]**: crippy is signed by the same key as this app. crippy's file manager is signed with
 *   crippy's key (crippy D115), so this is what lets it trust a build of crippy made on the same machine
 *   as itself, debug builds included, without naming a key.
 */
public class CrippyTrust private constructor(
    public val certificates: Set<String>,
    public val sameSigner: Boolean,
) {
    public companion object {
        /**
         * The certificates crippy's releases are signed with. **Empty until crippy has a release key**
         * (crippy D115): no build signed by anyone is trusted on these grounds alone until then.
         */
        public val RELEASE_CERTIFICATES: Set<String> = emptySet()

        /** crippy's release certificates, and a crippy signed by the same key as this app. */
        public val DEFAULT: CrippyTrust = CrippyTrust(RELEASE_CERTIFICATES, sameSigner = true)

        /**
         * [certificates] as well as crippy's release certificates. For an app developed against a crippy
         * built and signed somewhere else, such as a debug build.
         */
        public fun alsoTrusting(
            vararg certificates: String,
            sameSigner: Boolean = true,
        ): CrippyTrust {
            val digests = certificates.map { it.lowercase().replace(":", "") }
            require(digests.all { it.length == SHA256_HEX && it.all(::isHexDigit) }) {
                "a certificate is named by its SHA-256, 64 hex digits"
            }
            return CrippyTrust(RELEASE_CERTIFICATES + digests, sameSigner)
        }

        private const val SHA256_HEX = 64

        private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'
    }
}
